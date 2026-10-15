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

use std::collections::BTreeMap;

use io_pimdir::summary::derive;
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
    offline::{Driver, OfflineSync, summary::SummaryJson, wire::sorted},
};

/// `Native.offlineSync`: reconciles a collection, answering its sync report.
///
/// `{"pulled", "pushed", "conflicts", "rejected", "refreshed", "waiting"}`;
/// `full` skips the checkpoint, `content` pushes bodies, `since` bounds mail
/// on `Date` (RFC 3339 `Z`, or empty), `scope_bound` binds the checkpoint.
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
/// `handles` is a JSON string array. Answers `{"upgraded", "fetched",
/// "deduped"}`.
#[unsafe(no_mangle)]
pub extern "system" fn Java_org_pimalaya_client_Native_offlineUpgrade<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
    driver: JObject<'local>,
    collection: JString<'local>,
    handles: JString<'local>,
) -> JObject<'local> {
    env.with_env(|env| -> Result<JObject<'local>, Error> {
        let collection = read_string(env, &collection);
        let handles = read_string(env, &handles);

        let json = match parse_strings(&handles) {
            Err(err) => error_json(err),
            Ok(handles) => {
                match Driver::new(JniHost::offline(env, &driver)).upgrade(&collection, handles) {
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
/// "size", "body", "summary"}`. Answers `{}`.
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
            Ok(()) => "{}".to_string(),
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
/// A membership patch or a create or delete, plus Google's post-create
/// membership. Takes `{"op", "collection", "bookId"?, "origin", "deleted"}`,
/// answers `{"action", "postCreateBooks"?}`.
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
        let statements: BTreeMap<&str, &str> = io_pimdir::sql::all().collect();
        let json = to_string(&statements).unwrap_or_else(|err| error_json(err.to_string()));

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
        let json =
            to_string(io_pimdir::sql::MIGRATIONS).unwrap_or_else(|err| error_json(err.to_string()));

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
        let body = env.convert_byte_array(&body).unwrap_or_default();

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

/// The reply of `pimdirDerive`.
#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
struct DerivedReply<'a> {
    link_id: &'a str,
    sort_key: &'a str,
    #[serde(serialize_with = "sorted")]
    summary: Option<SummaryJson>,
}
