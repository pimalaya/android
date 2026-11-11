//! # Offline exports
//!
//! The exports of the offline engine, each serving its yields through the
//! given `OfflineDriver`, the store decisions (pure, no transport), and the
//! canonical pimdir SQL and Annex A derivation handed to Java.
//!
//! io-pimdir comes without its `client` feature, so no second SQLite engine
//! is compiled into every ABI beside the one Java bundles. The schema and
//! statements cross here rather than being transcribed into Java, where
//! they would drift from the spec in silence.

use std::{
    collections::BTreeMap,
    fs::File,
    io::{self, Read},
    path::Path,
};

use io_pimdir::{
    hash::{self, PimdirHasher},
    object::PimdirHash,
    summary::derive,
};
use jni::{
    EnvUnowned,
    errors::Error,
    objects::{JByteArray, JClass, JObject, JString},
    sys::jboolean,
};
use serde::Serialize;
use serde_json::to_string;

use crate::{
    contact::sync,
    ffi::{ErrorReply, error_json, host::JniHost, parse_facts, parse_strings, read_string},
    mail::derivable,
    offline::{
        Driver, OfflineSync,
        blobs::{self, Filed},
        merge, shape, store,
        summary::SummaryJson,
        wire::sorted,
    },
};

/// `Native.offlineSync`: reconciles a collection, answering its sync report.
///
/// `{"pulled", "pushed", "conflicts", "rejected", "refreshed", "waiting"}`;
/// `full` skips the checkpoint, `content` pushes bodies, `since` bounds mail
/// on `Date` (RFC 3339 `Z`, or empty), `scope_bound` binds the checkpoint,
/// `accept_absences` closes a round that would park (SYNC §5).
#[unsafe(no_mangle)]
pub extern "system" fn Java_org_pimalaya_client_Native_offlineSync<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
    driver: JObject<'local>,
    collection: JString<'local>,
    full: jboolean,
    content: jboolean,
    since: JString<'local>,
    scope_bound: jboolean,
    accept_absences: jboolean,
) -> JObject<'local> {
    env.with_env(|env| -> Result<JObject<'local>, Error> {
        let collection = read_string(env, &collection);
        let since = read_string(env, &since);

        let json = match Driver::new(JniHost::offline(env, &driver)).sync(&OfflineSync {
            collection: &collection,
            full,
            content,
            since: &since,
            scope_bound,
            accept_absences,
        }) {
            Ok(report) => to_string(&report).unwrap_or_else(|err| error_json(err.to_string())),
            Err(err) => error_json(err),
        };

        Ok(env.new_string(json)?.into())
    })
    .resolve::<ErrorReply>()
}

/// `Native.offlineUpgrade`: raises handles to the full detail tier.
///
/// `handles` is a JSON string array; `content` whether the collection's sync
/// pushes bodies, its policy settling a divergence the upgrade reads. Answers
/// `{"upgraded", "fetched", "deduped", "overruled"}`.
#[unsafe(no_mangle)]
pub extern "system" fn Java_org_pimalaya_client_Native_offlineUpgrade<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
    driver: JObject<'local>,
    collection: JString<'local>,
    handles: JString<'local>,
    content: jboolean,
) -> JObject<'local> {
    env.with_env(|env| -> Result<JObject<'local>, Error> {
        let collection = read_string(env, &collection);
        let handles = read_string(env, &handles);

        let json = match parse_strings(&handles) {
            Err(err) => error_json(err),
            Ok(handles) => {
                let mut driver = Driver::new(JniHost::offline(env, &driver));
                match driver.upgrade(&collection, handles, content) {
                    Ok(report) => {
                        to_string(&report).unwrap_or_else(|err| error_json(err.to_string()))
                    }
                    Err(err) => error_json(err),
                }
            }
        };

        Ok(env.new_string(json)?.into())
    })
    .resolve::<ErrorReply>()
}

/// `Native.offlineMutate`: stages a local mutation, the remote untouched.
///
/// The mutation is a JSON object, e.g. `{"op": "edit", "handle", "hash",
/// "size", "body", "summary"}`. Answers `{"overruled"}`.
#[unsafe(no_mangle)]
pub extern "system" fn Java_org_pimalaya_client_Native_offlineMutate<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
    driver: JObject<'local>,
    collection: JString<'local>,
    mutation: JString<'local>,
) -> JObject<'local> {
    env.with_env(|env| -> Result<JObject<'local>, Error> {
        let collection = read_string(env, &collection);
        let mutation = read_string(env, &mutation);

        let json = match Driver::new(JniHost::offline(env, &driver)).mutate(&collection, &mutation)
        {
            Ok(report) => to_string(&report).unwrap_or_else(|err| error_json(err.to_string())),
            Err(err) => error_json(err),
        };

        Ok(env.new_string(json)?.into())
    })
    .resolve::<ErrorReply>()
}

/// `Native.offlineRetryUnguarded`: whether a 412-rejected push may retry.
///
/// Unguarded, when the last enumerate proves the handle unchanged (the
/// CardDAV If-Match quirk). Takes `{"listed": {handle: etag}?, "complete",
/// "handle", "ifMatch"?}`, answers `{"retry": bool}`.
#[unsafe(no_mangle)]
pub extern "system" fn Java_org_pimalaya_client_Native_offlineRetryUnguarded<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
    facts: JString<'local>,
) -> JObject<'local> {
    env.with_env(|env| -> Result<JObject<'local>, Error> {
        let json = match parse_facts(env, &facts) {
            Err(err) => err,
            Ok(facts) => decision_json(Ok(sync::retry_unguarded(&facts))),
        };
        Ok(env.new_string(json)?.into())
    })
    .resolve::<ErrorReply>()
}

/// `Native.offlineAccountSnapshot`: projects an account-wide delta on a book.
///
/// For JMAP and Google. Takes `{"bookId"?, "complete", "changed": [{handle,
/// books, known}], "vanished"}`, answers `{"members": [index], "vanished":
/// [handle]}`.
#[unsafe(no_mangle)]
pub extern "system" fn Java_org_pimalaya_client_Native_offlineAccountSnapshot<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
    facts: JString<'local>,
) -> JObject<'local> {
    env.with_env(|env| -> Result<JObject<'local>, Error> {
        let json = match parse_facts(env, &facts) {
            Err(err) => err,
            Ok(facts) => decision_json(Ok(sync::account_snapshot(&facts))),
        };
        Ok(env.new_string(json)?.into())
    })
    .resolve::<ErrorReply>()
}

/// `Native.offlinePushPlan`: plans one push change.
///
/// A membership patch, a create, a delete or a hold, plus Google's
/// post-create membership. Takes `{"op", "collection", "bookId"?, "origin",
/// "deleted", "bound"}`, answers `{"action", "postCreateBooks"?}`.
#[unsafe(no_mangle)]
pub extern "system" fn Java_org_pimalaya_client_Native_offlinePushPlan<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
    facts: JString<'local>,
) -> JObject<'local> {
    env.with_env(|env| -> Result<JObject<'local>, Error> {
        let json = match parse_facts(env, &facts) {
            Err(err) => err,
            Ok(facts) => decision_json(sync::push_plan(&facts)),
        };
        Ok(env.new_string(json)?.into())
    })
    .resolve::<ErrorReply>()
}

/// Serializes a store decision, or its error.
fn decision_json(decision: Result<impl Serialize, String>) -> String {
    match decision {
        Ok(value) => to_string(&value).unwrap_or_else(|err| error_json(err.to_string())),
        Err(err) => error_json(err),
    }
}

/// `Native.pimdirSql`: every statement, keyed by its constant name.
///
/// A JSON object, canonical statements then the crate's own. Pure: no
/// transport, no store, no failure beyond serialization.
#[unsafe(no_mangle)]
pub extern "system" fn Java_org_pimalaya_client_Native_pimdirSql<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
) -> JObject<'local> {
    env.with_env(|env| -> Result<JObject<'local>, Error> {
        let json =
            to_string(&served_statements()).unwrap_or_else(|err| error_json(err.to_string()));

        Ok(env.new_string(json)?.into())
    })
    .resolve::<ErrorReply>()
}

/// `Native.pimdirMigrations`: every canonical migration in order.
///
/// A JSON array; the STORAGE §6 runner applies each above `user_version`.
/// Creating the database is the caller's job as much as querying it, so
/// migrations are indexed apart from the statements the profiles run.
#[unsafe(no_mangle)]
pub extern "system" fn Java_org_pimalaya_client_Native_pimdirMigrations<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
) -> JObject<'local> {
    env.with_env(|env| -> Result<JObject<'local>, Error> {
        let json = to_string(served_migrations()).unwrap_or_else(|err| error_json(err.to_string()));

        Ok(env.new_string(json)?.into())
    })
    .resolve::<ErrorReply>()
}

/// `Native.pimdirVersion`: the schema version of the compiled-in SQL.
///
/// The caller stamps it into `PRAGMA user_version` and checks it on open.
#[unsafe(no_mangle)]
pub extern "system" fn Java_org_pimalaya_client_Native_pimdirVersion<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
) -> i32 {
    env.with_env(|_| -> Result<i32, Error> { Ok(io_pimdir::sql::VERSION as i32) })
        .resolve::<ErrorReply>()
}

/// `Native.pimdirDerive`: what STORAGE Annex A derives from one body.
///
/// `kind` is its collection's media type; answers `{"linkId", "summary",
/// "sortKey"}`, `summary` null where the body yields none. Pure, so a body
/// is named the way any writer of the store would, nothing transcribed.
#[unsafe(no_mangle)]
pub extern "system" fn Java_org_pimalaya_client_Native_pimdirDerive<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
    kind: JString<'local>,
    body: JByteArray<'local>,
) -> JObject<'local> {
    env.with_env(|env| -> Result<JObject<'local>, Error> {
        let kind = read_string(env, &kind);
        let body = env.convert_byte_array(&body)?;

        // NOTE: a mail too deep for the library's walk is derived from
        // its header alone (`mail::derivable`).
        let body = match kind.eq_ignore_ascii_case("message/rfc822") {
            true => derivable(&body),
            false => &body,
        };
        let json = match derive(&kind, body) {
            None => error_json(format!("No pimdir derivation for {kind}")),
            Some(derived) => to_string(&DerivedReply {
                link_id: &derived.link_id.0,
                sort_key: derived.sort_key.as_str(),
                summary: derived.summary.as_ref().map(SummaryJson::from),
            })
            .unwrap_or_else(|err| error_json(err.to_string())),
        };

        Ok(env.new_string(json)?.into())
    })
    .resolve::<ErrorReply>()
}

/// `Native.pimdirHash`: the name the store files a body under (STORAGE
/// §5), BLAKE3 as 52 characters of lowercase base32. Pure.
#[unsafe(no_mangle)]
pub extern "system" fn Java_org_pimalaya_client_Native_pimdirHash<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
    body: JByteArray<'local>,
) -> JObject<'local> {
    env.with_env(|env| -> Result<JObject<'local>, Error> {
        let body = env.convert_byte_array(&body)?;
        Ok(env.new_string(hash::hash(&body).0)?.into())
    })
    .resolve::<ErrorReply>()
}

/// `Native.pimdirHashFile`: the name of a file's bytes, read whole a buffer
/// at a time; answers `{"hash"}`, or `{"error"}` when it cannot be read.
#[unsafe(no_mangle)]
pub extern "system" fn Java_org_pimalaya_client_Native_pimdirHashFile<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
    path: JString<'local>,
) -> JObject<'local> {
    env.with_env(|env| -> Result<JObject<'local>, Error> {
        let path = read_string(env, &path);
        let json = match hash_file(Path::new(&path)) {
            Ok(hash) => serde_json::json!({ "hash": hash.0 }).to_string(),
            Err(err) => error_json(format!("Could not hash {path}: {err}")),
        };
        Ok(env.new_string(json)?.into())
    })
    .resolve::<ErrorReply>()
}

/// `Native.pimdirIdentity`: the versions a store is created with and
/// opened at, `{"formatVersion", "syncVersion", "derivation", "version",
/// "hashAlgo"}` (STORAGE §4, §6). Pure.
#[unsafe(no_mangle)]
pub extern "system" fn Java_org_pimalaya_client_Native_pimdirIdentity<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
) -> JObject<'local> {
    env.with_env(|env| -> Result<JObject<'local>, Error> {
        let json = to_string(&store::identity()).unwrap_or_else(|err| error_json(err.to_string()));
        Ok(env.new_string(json)?.into())
    })
    .resolve::<ErrorReply>()
}

/// `Native.pimdirCheck`: whether the app opens an existing store, from the
/// facts Java read off it without writing (`StoreFacts`); answers `{}`, or
/// `{"refused": code, "detail"}` for a store of another draft, a damaged
/// or a foreign one (STORAGE §6). Pure.
#[unsafe(no_mangle)]
pub extern "system" fn Java_org_pimalaya_client_Native_pimdirCheck<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
    facts: JString<'local>,
) -> JObject<'local> {
    env.with_env(|env| -> Result<JObject<'local>, Error> {
        let facts = read_string(env, &facts);
        let json = match serde_json::from_str::<store::StoreFacts>(&facts) {
            Err(err) => error_json(format!("Unreadable store facts: {err}")),
            Ok(facts) => match store::check(&facts) {
                Ok(()) => "{}".to_string(),
                Err(refusal) => serde_json::json!({
                    "refused": refusal.code,
                    "detail": refusal.detail,
                })
                .to_string(),
            },
        };
        Ok(env.new_string(json)?.into())
    })
    .resolve::<ErrorReply>()
}

/// `Native.pimdirAction`: a queue row read strictly as its payload schema
/// types it (STORAGE §15.3), its body the one the row pins; answers
/// `{"targetCollection"?}`, `{"skipped"}` for a row stating a later
/// payload version, which the drain leaves pending (§15.2), or `{"error"}`
/// for a malformed row, which a producer never appends and the drain parks
/// whole. `objectHash` is empty for a row pinning none. Pure.
#[unsafe(no_mangle)]
pub extern "system" fn Java_org_pimalaya_client_Native_pimdirAction<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
    kind: JString<'local>,
    payload: JString<'local>,
    object_hash: JString<'local>,
) -> JObject<'local> {
    env.with_env(|env| -> Result<JObject<'local>, Error> {
        let kind = read_string(env, &kind);
        let payload = read_string(env, &payload);
        let object_hash = read_string(env, &object_hash);
        let object_hash = Some(object_hash.as_str()).filter(|hash| !hash.is_empty());
        let json = match store::action(&kind, &payload, object_hash) {
            Ok(facts) => to_string(&facts).unwrap_or_else(|err| error_json(err.to_string())),
            Err(err) => error_json(format!("Malformed {kind} payload: {err}")),
        };
        Ok(env.new_string(json)?.into())
    })
    .resolve::<ErrorReply>()
}

/// `Native.pimdirSubmit`: the `submit` payload queuing `message` for
/// `source` to send from `from` (STORAGE Annex B.2), its recipients and
/// subject read off the message; answers `{"payload"}`, or `{"error"}` for
/// a message naming no recipient. Pure.
#[unsafe(no_mangle)]
pub extern "system" fn Java_org_pimalaya_client_Native_pimdirSubmit<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
    source: JString<'local>,
    from: JString<'local>,
    message: JByteArray<'local>,
) -> JObject<'local> {
    env.with_env(|env| -> Result<JObject<'local>, Error> {
        let source = read_string(env, &source);
        let from = read_string(env, &from);
        let message = env.convert_byte_array(&message)?;
        let json = match store::submit_payload(&source, &from, &message) {
            Ok(payload) => serde_json::json!({ "payload": payload }).to_string(),
            Err(err) => error_json(err),
        };
        Ok(env.new_string(json)?.into())
    })
    .resolve::<ErrorReply>()
}

/// `Native.pimdirMerge`: one upsert absorbed into the item it names by
/// io-pimdir's hub (SYNC §9, [`merge::merge`]): takes `{policy?, source,
/// item?, bindings, placement, overruling?}`, answers `{object?,
/// conflicted, conflictObject?, sharedObject?, diverged, keptShared}`, or
/// `{"error"}` for input it cannot read. Pure.
#[unsafe(no_mangle)]
pub extern "system" fn Java_org_pimalaya_client_Native_pimdirMerge<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
    input: JString<'local>,
) -> JObject<'local> {
    env.with_env(|env| -> Result<JObject<'local>, Error> {
        let input = read_string(env, &input);
        let json = match serde_json::from_str(&input) {
            Ok(input) => {
                to_string(&merge::merge(input)).unwrap_or_else(|err| error_json(err.to_string()))
            }
            Err(err) => error_json(format!("Unreadable merge input: {err}")),
        };
        Ok(env.new_string(json)?.into())
    })
    .resolve::<ErrorReply>()
}

/// `Native.pimdirRename`: renames `from` to `to` unless a name holds `to`,
/// never replacing it (STORAGE §5, [`blobs::rename_noreplace_on`]),
/// through `renameat2` only where `renameat2` says the platform allows it
/// (API 30 and later); answers `{"filed"}`: `moved`, `held` when the name
/// was kept, its file hashing to it, and `failing` when the file holding it
/// fails it (STORAGE §5), `from` left where it was in both; or `{"error"}`.
#[unsafe(no_mangle)]
pub extern "system" fn Java_org_pimalaya_client_Native_pimdirRename<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
    from: JString<'local>,
    to: JString<'local>,
    renameat2: jboolean,
) -> JObject<'local> {
    env.with_env(|env| -> Result<JObject<'local>, Error> {
        let from = read_string(env, &from);
        let to = read_string(env, &to);
        let filed = blobs::rename_noreplace_on(Path::new(&from), Path::new(&to), renameat2);
        let json = match filed {
            Ok(filed) => {
                let filed = match filed {
                    Filed::Moved => "moved",
                    Filed::Held => "held",
                    Filed::Failing => "failing",
                };
                serde_json::json!({ "filed": filed }).to_string()
            }
            Err(err) => error_json(format!("Could not rename {from} to {to}: {err}")),
        };
        Ok(env.new_string(json)?.into())
    })
    .resolve::<ErrorReply>()
}

/// The BLAKE3 name of a file's bytes, read a buffer at a time.
fn hash_file(path: &Path) -> io::Result<PimdirHash> {
    let mut file = File::open(path)?;
    let mut hasher = PimdirHasher::new();
    let mut buffer = vec![0; 64 * 1024];
    loop {
        match file.read(&mut buffer)? {
            0 => return Ok(hasher.finish()),
            read => hasher.update(&buffer[..read]),
        }
    }
}

/// The reply of `pimdirDerive`.
#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
struct DerivedReply<'a> {
    link_id: &'a str,
    sort_key: &'a str,
    #[serde(serialize_with = "sorted")]
    summary: Option<SummaryJson>,
}

/// The statements `Native.pimdirSql` serves, keyed by their constant name:
/// the app's shape ([`shape`]).
fn served_statements() -> BTreeMap<&'static str, &'static str> {
    shape::statements()
}

/// The migrations `Native.pimdirMigrations` serves, in order.
fn served_migrations() -> &'static [&'static str] {
    shape::migrations()
}

#[cfg(test)]
mod tests {
    use std::{
        env,
        io::Write,
        process::{Command, Stdio},
    };

    use crate::{
        ffi::offline::{served_migrations, served_statements},
        offline::{shape, store},
    };

    /// The SQLite of Android 8.0, the oldest the app runs on (flake.nix).
    const FLOOR: &str = "3.18.2";

    /// Every served migration runs, in order on a fresh database, and every
    /// served statement prepares against the schema they leave, on the
    /// floor build of PIMALAYA_SQLITE_FLOOR.
    #[test]
    fn served_sql_holds_to_the_sqlite_floor() {
        let floor = env::var("PIMALAYA_SQLITE_FLOOR")
            .expect("PIMALAYA_SQLITE_FLOOR is unset: run the tests through `nix develop`");

        let statements = served_statements();
        let migrations = served_migrations()
            .iter()
            .enumerate()
            .map(|(index, sql)| ("migration", format!("{:04}", index + 1), *sql));
        // NOTE: the list parameters' table is per connection, created by
        // CREATE_PARAM before a statement reading one runs (STORAGE §4.4),
        // so it is run on the floor too, after the schema.
        let param = (
            "migration",
            "CREATE_PARAM".to_owned(),
            statements["CREATE_PARAM"],
        );
        // NOTE: a wrapping read of the app's own is prepared over every read
        // it wraps, as Java runs it.
        let wrapped = shape::prepared();
        let prepared = wrapped
            .iter()
            .map(|(name, sql)| ("statement", name.clone(), sql.as_str()));

        let mut input = Vec::new();
        for (kind, name, sql) in migrations.chain([param]).chain(prepared) {
            for part in [kind, name.as_str(), sql] {
                input.extend_from_slice(part.as_bytes());
                input.push(0);
            }
        }

        let mut check = Command::new(format!("{floor}/bin/pimalaya-sqlite-floor-check"))
            .stdin(Stdio::piped())
            .stdout(Stdio::piped())
            .spawn()
            .expect("the floor check should start");
        // NOTE: the check reads all its input before it writes a line.
        check.stdin.take().unwrap().write_all(&input).unwrap();
        let output = check.wait_with_output().unwrap();
        let report = String::from_utf8_lossy(&output.stdout);

        let mut lines = report.lines();
        assert_eq!(lines.next(), Some(format!("sqlite {FLOOR}").as_str()));
        let failures: Vec<&str> = lines.collect();
        assert!(
            output.status.success() && failures.is_empty(),
            "{} failures on SQLite {FLOOR} ({}):\n{}",
            failures.len(),
            output.status,
            failures.join("\n"),
        );
    }

    /// A store a newer SQLite wrote opens on the floor build, as one requery's
    /// 3.49 wrote on a device opens on the platform's 3.18 since
    /// platform-sqlite step 3: the canonical schema under write-ahead
    /// logging, its rows read, written and checked whole.
    #[test]
    fn a_store_a_newer_sqlite_wrote_opens_on_the_floor() {
        let floor = env::var("PIMALAYA_SQLITE_FLOOR")
            .expect("PIMALAYA_SQLITE_FLOOR is unset: run the tests through `nix develop`");
        let dir = tempfile::tempdir().unwrap();
        let path = dir.path().join("pimdir.db");

        let newer = rusqlite::Connection::open(&path).unwrap();
        let version: String = newer
            .query_row("SELECT sqlite_version()", [], |row| row.get(0))
            .unwrap();
        assert_ne!(version, FLOOR, "the store is written by a newer SQLite");
        newer
            .execute_batch("PRAGMA auto_vacuum = INCREMENTAL; PRAGMA journal_mode = WAL;")
            .unwrap();
        for migration in served_migrations() {
            newer.execute_batch(migration).unwrap();
        }
        let identity = store::identity();
        newer
            .execute(
                served_statements()["INIT_STORE_META"],
                rusqlite::named_params! {
                    ":version": identity.version,
                    ":derivation": identity.derivation,
                },
            )
            .unwrap();
        newer
            .execute_batch(
                "INSERT INTO collections(id, account, kind, name)
                   VALUES('INBOX', 'a', 'message/rfc822', 'INBOX');
                 INSERT INTO items(collection, link_id, seq, level)
                   VALUES('INBOX', 'm1', 1, 1);
                 INSERT INTO mail_summary(item, subject)
                   VALUES((SELECT id FROM items WHERE link_id = 'm1'), 'one');",
            )
            .unwrap();
        newer
            .pragma_update(None, "user_version", identity.version)
            .unwrap();
        drop(newer);

        let output = Command::new(format!("{floor}/bin/sqlite3"))
            .arg(&path)
            .arg(
                "PRAGMA foreign_keys = ON;
                 SELECT sqlite_version();
                 PRAGMA journal_mode;
                 SELECT format_version FROM store_meta;
                 UPDATE mail_summary SET subject = 'two';
                 SELECT subject FROM mail_summary;
                 PRAGMA integrity_check;
                 PRAGMA foreign_key_check;",
            )
            .output()
            .unwrap();
        assert!(
            output.status.success(),
            "{}",
            String::from_utf8_lossy(&output.stderr)
        );
        assert_eq!(
            String::from_utf8_lossy(&output.stdout),
            format!("{FLOOR}\nwal\nstorage-draft-09\ntwo\nok\n"),
        );
    }
}
