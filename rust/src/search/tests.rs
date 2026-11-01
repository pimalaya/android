//! The index on the floor: the shape's SQL prepared on SQLite 3.18.2, a
//! store indexed and refreshed through the bridge's steps run by the
//! floor's own library ([`Floor`]), and io-pimdir's search vectors
//! (pimdir SEARCH §11) answered by the shape.

use std::{
    collections::{BTreeMap, BTreeSet},
    env, fs,
    io::Write,
    path::{Path, PathBuf},
    process::{Command, Stdio},
};

use io_pimdir::{
    change::{PimdirDropReason, PimdirWriteOp},
    client::PimdirStore,
    collection::{PimdirCollectionId, PimdirScope},
    object::{PimdirHash, PimdirObject},
    placement::{
        PimdirFlags, PimdirHandle, PimdirLevel, PimdirLinkId, PimdirPlacement, PimdirSortKey,
        PimdirStatus,
    },
    reference::{PimdirEndpoint, PimdirReferenceOrigin, PimdirReferenceRole},
    search::query::PimdirQuery,
    summary::{
        self, PimdirAddress, PimdirSummary, file::PimdirFileSummary, mail::PimdirMailSummary,
    },
};
use serde_json::Value as Json;

use crate::{
    offline::shape as store_shape,
    search::{
        compile::{Compiled, Sort},
        find::{self, Answer, Hit},
        floor::{FLOOR, Floor},
        held::{self, Held},
        refresh,
        shape::{self, APP, DROPPED, SHAPE},
        sql::{IndexSql, int, query, text},
    },
};

/// io-pimdir's vendored spec at the pinned revision, the cargo checkout
/// of the rev rust/Cargo.toml patches in, or `PIMALAYA_IO_PIMDIR_SPEC`.
fn spec() -> PathBuf {
    if let Ok(dir) = env::var("PIMALAYA_IO_PIMDIR_SPEC") {
        return PathBuf::from(dir);
    }
    let manifest = include_str!("../../Cargo.toml");
    let line = manifest
        .lines()
        .find(|line| line.starts_with("io-pimdir = { git"))
        .expect("io-pimdir is patched to a revision");
    let rev = line.split("rev = \"").nth(1).unwrap();
    let short = &rev[..7];
    let home = env::var("CARGO_HOME")
        .map(PathBuf::from)
        .unwrap_or_else(|_| PathBuf::from(env::var("HOME").unwrap()).join(".cargo"));
    let checkouts = home.join("git/checkouts");
    for entry in fs::read_dir(&checkouts).expect("cargo's git checkouts") {
        let dir = entry.unwrap().path().join(short);
        let name = dir.parent().unwrap().file_name().unwrap().to_string_lossy();
        if name.starts_with("io-pimdir-") && dir.join("spec").is_dir() {
            return dir.join("spec");
        }
    }
    panic!(
        "no io-pimdir checkout at {short} under {}",
        checkouts.display()
    );
}

fn json(path: &Path) -> Json {
    serde_json::from_str(&fs::read_to_string(path).unwrap()).unwrap()
}

/// An index.db beside a store at `dir`, the store attached, on the floor.
fn index(dir: &Path) -> Floor {
    let mut floor = Floor::open(&dir.join("index.db"));
    let store = dir.join("pimdir.db");
    floor.exec(&format!(
        "ATTACH DATABASE '{}' AS store",
        store.to_str().unwrap()
    ));
    floor
}

/// The text of a statement the owner runs itself, as Java runs it.
fn served(name: &str) -> String {
    shape::steps(shape::text(name).unwrap())
        .into_iter()
        .next()
        .unwrap()
        .sql
}

/// Builds the index if empty and folds the whole feed, a run a
/// transaction, at `now`; answers the runs.
fn refreshed(floor: &mut Floor, dir: &Path, now: i64, batch: usize) -> usize {
    match held::held(floor).unwrap() {
        Held::Empty => {
            floor.exec(&served("BEGIN_RUN"));
            held::init(floor).unwrap();
            floor.exec(&served("COMMIT_RUN"));
        }
        Held::Current => (),
        other => panic!("index held as {other:?}"),
    }
    assert_eq!(held::held(floor).unwrap(), Held::Current);
    let blobs = dir.join("objects");
    let mut runs = 0;
    loop {
        floor.exec(&served("BEGIN_RUN"));
        let output = refresh::run(floor, &blobs, now, batch, runs == 0).unwrap();
        floor.exec(&served("COMMIT_RUN"));
        runs += 1;
        if output.exhausted {
            return runs;
        }
        assert!(runs < 10_000, "the refresh never exhausts the feed");
    }
}

/// The steps a statement file runs, each `(kind, name, text)` the floor
/// check reads.
fn checked<'a>(kind: &'a str, name: String, text: &str) -> Vec<(&'a str, String, String)> {
    vec![(kind, name, text.to_string())]
}

/// Every index migration runs on 3.18.2, the store attached, and every
/// served index statement prepares against the schema they leave, as does
/// every query of the vectors compiled; the floor's check, as the store's
/// own guard runs it (rust/src/ffi/offline.rs).
#[test]
fn served_index_sql_holds_to_the_sqlite_floor() {
    let floor = env::var("PIMALAYA_SQLITE_FLOOR")
        .expect("PIMALAYA_SQLITE_FLOOR is unset: run the tests through `nix develop`");
    let dir = tempfile::tempdir().unwrap();
    let store = dir.path().join("pimdir.db");
    let mut shell = Command::new(format!("{floor}/bin/sqlite3"))
        .arg(&store)
        .stdin(Stdio::piped())
        .spawn()
        .unwrap();
    let mut schema = String::new();
    for migration in store_shape::migrations() {
        schema += migration;
        schema += "\n";
    }
    shell
        .stdin
        .take()
        .unwrap()
        .write_all(schema.as_bytes())
        .unwrap();
    assert!(shell.wait().unwrap().success());

    let mut texts = checked(
        "migration",
        "ATTACH".into(),
        &format!("ATTACH DATABASE '{}' AS store;", store.display()),
    );
    for (at, migration) in shape::migrations().iter().enumerate() {
        texts.extend(checked("migration", format!("{:04}", at + 1), migration));
    }
    let statements = shape::statements();
    for name in ["CREATE_PARAM", "CREATE_TERM_MATCH"] {
        let text = shape::text(name).unwrap();
        texts.extend(checked("migration", name.into(), text));
    }
    for (name, text) in statements {
        // NOTE: the attach runs on the platform's own call, never prepared
        // beside the schema it attaches.
        if *name != "ATTACH_STORE" {
            texts.extend(checked("statement", name.to_string(), text));
        }
    }
    let vectors = json(&spec().join("vectors/search/queries.json"));
    for case in vectors["cases"].as_array().unwrap() {
        let Ok(parsed) = PimdirQuery::parse(case["query"].as_str().unwrap()) else {
            continue;
        };
        let ends = Vec::new();
        let mut compiled = Compiled::new(&parsed, ends, Vec::new(), 0);
        for sort in [Sort::Date, Sort::Name] {
            let select = compiled.select(sort, Some((10, 0)));
            texts.extend(checked("statement", case["query"].to_string(), &select));
        }
        for matches in &compiled.matches {
            texts.extend(checked(
                "statement",
                case["query"].to_string(),
                &matches.sql,
            ));
        }
    }

    let mut input = Vec::new();
    for (kind, name, sql) in &texts {
        for part in [*kind, name.as_str(), sql.as_str()] {
            input.extend_from_slice(part.as_bytes());
            input.push(0);
        }
    }
    let mut check = Command::new(format!("{floor}/bin/pimalaya-sqlite-floor-check"))
        .stdin(Stdio::piped())
        .stdout(Stdio::piped())
        .spawn()
        .unwrap();
    check.stdin.take().unwrap().write_all(&input).unwrap();
    let output = check.wait_with_output().unwrap();
    let report = String::from_utf8_lossy(&output.stdout);
    let mut lines = report.lines();
    assert_eq!(lines.next(), Some(format!("sqlite {FLOOR}").as_str()));
    let failures: Vec<&str> = lines.collect();
    assert!(
        output.status.success() && failures.is_empty(),
        "{} failures on SQLite {FLOOR}:\n{}",
        failures.len(),
        failures.join("\n"),
    );
}

#[test]
fn the_floor_library_is_the_floor() {
    assert_eq!(Floor::version(), FLOOR);
}

/// `-- Fork of <name> (sha256 <hex>)`: the canonical text a fork names.
fn forked_digest(text: &str) -> Option<&str> {
    let start = text.find("(sha256 ")? + "(sha256 ".len();
    let end = start + text[start..].find(')')?;
    Some(&text[start..end])
}

#[test]
fn every_index_fork_names_the_canonical_text_it_rewrites() {
    use sha2::{Digest, Sha256};

    let mut canonical: BTreeMap<&str, &str> = io_pimdir::sql::search::all().collect();
    canonical.insert("MIGRATION_0001", io_pimdir::sql::search::MIGRATIONS[0]);
    let mut forks: Vec<(&str, &str)> = crate::offline::shape::INDEX
        .iter()
        .filter(|(_, profile, _)| *profile != APP)
        .map(|(name, _, text)| (*name, *text))
        .collect();
    forks.push(("MIGRATION_0001", shape::migrations()[0]));
    for (name, text) in forks {
        let original = canonical
            .get(name)
            .unwrap_or_else(|| panic!("search/{name} forks no canonical statement"));
        let digest: String = Sha256::digest(original.as_bytes())
            .iter()
            .map(|byte| format!("{byte:02x}"))
            .collect();
        assert_eq!(
            forked_digest(text),
            Some(&digest[..16]),
            "search/{name} was forked from another canonical text: read the \
             canonical one again, then restate its digest",
        );
    }
    for (name, profile, _) in crate::offline::shape::INDEX {
        if *profile == APP {
            assert!(
                !canonical.contains_key(name),
                "index/{name} shadows a canonical one"
            );
        }
    }
    for name in DROPPED {
        assert!(!shape::statements().contains_key(name), "{name} is served");
    }
}

/// One placement, the owner's write of what a source listed.
fn placement(
    collection: &str,
    handle: &str,
    link_id: &str,
    object: Option<PimdirHash>,
    summary: PimdirSummary,
    sort_key: &str,
    flags: PimdirFlags,
) -> PimdirPlacement {
    PimdirPlacement {
        sort_key: PimdirSortKey(sort_key.into()),
        collection: PimdirCollectionId(collection.into()),
        handle: PimdirHandle(handle.into()),
        link_id: Some(PimdirLinkId(link_id.into())),
        level: match object {
            Some(_) => PimdirLevel::Full,
            None => PimdirLevel::Meta,
        },
        object,
        summary: Some(summary),
        flags,
        status: PimdirStatus::Clean,
        conflict_revision: None,
        conflict_object: None,
        base: None,
        origin: None,
    }
}

fn strings(value: &Json) -> Vec<String> {
    value
        .as_array()
        .map(|values| {
            values
                .iter()
                .map(|value| value.as_str().unwrap().to_string())
                .collect()
        })
        .unwrap_or_default()
}

/// A bodiless mail summary as a vector states it.
fn mail_summary(row: &Json, addresses: &[Json]) -> PimdirSummary {
    let role = |role: &str| -> Vec<PimdirAddress> {
        addresses
            .iter()
            .filter(|address| address["role"] == role)
            .map(|address| PimdirAddress {
                address: address["address"].as_str().unwrap().into(),
                name: address["name"].as_str().map(Into::into),
            })
            .collect()
    };
    let text = |key: &str| row[key].as_str().map(String::from);
    PimdirSummary::Mail(PimdirMailSummary {
        message_id: text("message_id"),
        in_reply_to: strings(&row["in_reply_to"]),
        subject: text("subject").unwrap_or_default(),
        sender: text("sender"),
        sender_name: text("sender_name"),
        date: text("date"),
        size: row["size"].as_u64(),
        attachment: row["attachment"].as_i64().map(|mark| mark != 0),
        invitation: text("invitation"),
        from: role("from"),
        to: role("to"),
        cc: role("cc"),
        bcc: role("bcc"),
    })
}

/// Builds the vectors' fixture store at `dir` through io-pimdir's owner,
/// as io-pimdir's own suite does (tests/search.rs).
fn build(spec: &Path, dir: &Path) {
    let store = json(&spec.join("vectors/search/store.json"));
    let mut kinds = BTreeMap::new();
    let mut stand_ins = BTreeSet::new();
    for collection in store["collections"].as_array().unwrap() {
        let id = collection["id"].as_str().unwrap();
        let kind = collection["kind"].as_str().unwrap();
        let account = collection["account"].as_str().unwrap();
        let owner = PimdirStore::open(dir).unwrap().for_account(account);
        owner.ensure_collection(id, kind).unwrap();
        if let Some(role) = collection["role"].as_str() {
            owner.set_collection_role(id, Some(role)).unwrap();
            if role == "attachments" {
                stand_ins.insert(id.to_string());
            }
        }
        kinds.insert(id.to_string(), kind.to_string());
        drop(owner);
        for source in collection["sources"].as_array().into_iter().flatten() {
            let scope = PimdirScope {
                since: source["covered_since"].as_str().map(Into::into),
                until: source["covered_until"].as_str().map(Into::into),
            };
            let mut ops = vec![PimdirWriteOp::OpenRound {
                collection: PimdirCollectionId(id.into()),
                scope: scope.clone(),
                band: false,
            }];
            if !source["covered_at"].is_null() {
                ops.push(PimdirWriteOp::CloseRound {
                    collection: PimdirCollectionId(id.into()),
                    coverage: scope,
                    checkpoint: None,
                });
            }
            PimdirStore::open(dir)
                .unwrap()
                .for_account(account)
                .for_source(source["source"].as_str().unwrap())
                .write(ops)
                .unwrap();
        }
    }

    let mut owner = PimdirStore::open(dir).unwrap().for_source("vectors");
    for item in store["items"].as_array().unwrap() {
        let collection = item["collection"].as_str().unwrap();
        let link_id = item["link_id"].as_str().unwrap();
        let seq = item["seq"].as_i64().unwrap();
        let flags: PimdirFlags = strings(&item["flags"]).into_iter().collect();
        let kind = &kinds[collection];
        if stand_ins.contains(collection) {
            // NOTE: the owner records a stand-in as its message's body
            // enters (STORAGE §14.3).
        } else if let Some(fixture) = item["fixture"].as_str() {
            let body = fs::read(spec.join("vectors").join(fixture)).unwrap();
            let derived = summary::derive(kind, &body).unwrap();
            let hash = owner.hash(&body);
            owner
                .write(vec![
                    PimdirWriteOp::StoreObject {
                        object: PimdirObject {
                            hash: hash.clone(),
                            size: body.len(),
                        },
                        body: Some(body),
                    },
                    PimdirWriteOp::UpsertPlacement(placement(
                        collection,
                        &seq.to_string(),
                        link_id,
                        Some(hash),
                        derived.summary.unwrap(),
                        derived.sort_key.as_str(),
                        flags,
                    )),
                ])
                .unwrap();
        } else {
            let stated = &item["summary"];
            let row = &stated["row"];
            match stated["table"].as_str().unwrap() {
                "mail_summary" => {
                    let addresses = stated["addresses"].as_array().unwrap();
                    owner
                        .write(vec![PimdirWriteOp::UpsertPlacement(placement(
                            collection,
                            &seq.to_string(),
                            link_id,
                            None,
                            mail_summary(row, addresses),
                            stated["sort_key"].as_str().unwrap(),
                            flags,
                        ))])
                        .unwrap();
                }
                "file_summary" => {
                    let file = PimdirFileSummary {
                        name: row["name"].as_str().unwrap().into(),
                        media_type: row["media_type"].as_str().map(Into::into),
                        size: row["size"].as_u64(),
                        part: row["part"].as_str().map(Into::into),
                    };
                    owner
                        .put_file(collection, &PimdirLinkId(link_id.into()), &file)
                        .unwrap();
                }
                table => panic!("no fixture builder for {table}"),
            }
        }
        let placed = owner.seq_for_link(collection, link_id).unwrap();
        assert_eq!(placed, Some(seq), "{link_id} draws the vector's seq");
    }

    for reference in store["references"].as_array().unwrap() {
        let end = |kind: &str, link: &str| PimdirEndpoint {
            kind: reference[kind].as_str().unwrap().into(),
            link_id: PimdirLinkId(reference[link].as_str().unwrap().into()),
        };
        owner
            .add_reference(
                &end("from_kind", "from_link_id"),
                &end("to_kind", "to_link_id"),
                &PimdirReferenceRole::parse(reference["role"].as_str().unwrap()),
                PimdirReferenceOrigin::parse(reference["origin"].as_str().unwrap()),
            )
            .unwrap()
            .expect("the reference links");
    }
}

/// An RFC 3339 instant in UTC as Unix seconds.
fn unix(instant: &str) -> i64 {
    jiff::Timestamp::strptime("%Y-%m-%dT%H:%M:%SZ", instant)
        .or_else(|_| instant.parse::<jiff::Timestamp>())
        .unwrap()
        .as_second()
}

/// A hit list as `(account, seq)` pairs.
fn pairs(hits: &Json) -> Vec<(String, i64)> {
    hits.as_array()
        .unwrap()
        .iter()
        .map(|hit| {
            (
                hit[0].as_str().unwrap().to_string(),
                hit[1].as_i64().unwrap(),
            )
        })
        .collect()
}

/// Why a case of queries.json does not bind this shape, `None` when it
/// does (SEARCH §11, §12).
fn unbound(case: &Json) -> Option<&'static str> {
    if case["sort"] == "relevance" {
        return Some("relevance: the shape ranks nothing and refuses the order (SEARCH §8.2)");
    }
    if case.get("threads").is_some() {
        return Some("a threaded listing, which the app does not offer");
    }
    None
}

/// What a case answers on the shape, against what it states; `None` when
/// they agree.
fn answers(floor: &mut Floor, now: i64, case: &Json) -> Option<String> {
    let query = case["query"].as_str().unwrap();
    let sort = match case["sort"].as_str() {
        Some("name") => Sort::Name,
        _ => Sort::Date,
    };
    let found = match find::hits(floor, query, sort, None, now) {
        Err(err) => return Some(format!("{query:?}: {err}")),
        Ok(Answer::Refused(refused)) => {
            let Some(error) = case.get("error") else {
                return Some(format!("{query:?} refused: {}", refused.message));
            };
            let want = (
                error["code"].as_str().unwrap(),
                error["start"].as_u64().unwrap() as usize,
                error["end"].as_u64().unwrap() as usize,
            );
            let got = (refused.code, refused.start, refused.end);
            return (got != want).then(|| format!("{query:?}: refused {got:?}, not {want:?}"));
        }
        Ok(Answer::Found(found)) => found,
    };
    if case.get("error").is_some() {
        return Some(format!("{query:?} answered, not refused"));
    }
    let got: Vec<(String, i64)> = found
        .into_iter()
        .map(|Hit { account, seq }| (account.unwrap_or_default(), seq))
        .collect();
    let want = pairs(&case["hits"]);
    let same = match case["ordered"].as_bool() == Some(true) {
        true => got == want,
        false => {
            got.iter().collect::<BTreeSet<_>>() == want.iter().collect::<BTreeSet<_>>()
                && got.len() == want.len()
        }
    };
    (!same).then(|| format!("{query:?}: {got:?}, not {want:?}"))
}

/// Every case of io-pimdir's vendored queries.json the shape binds answers
/// as it says on SQLite 3.18.2, the store indexed by the bridge's refresh;
/// every occurrence window too.
#[test]
fn the_search_vectors_answer_on_the_floor() {
    let spec = spec();
    let dir = tempfile::tempdir().unwrap();
    build(&spec, dir.path());
    let vectors = json(&spec.join("vectors/search/queries.json"));
    assert_eq!(vectors["v"], 2, "queries.json at another version");
    let now = unix(vectors["now"].as_str().unwrap());

    let mut floor = index(dir.path());
    refreshed(&mut floor, dir.path(), now, 3);

    let mut failures = Vec::new();
    let mut skipped = Vec::new();
    let mut ran = 0;
    for case in vectors["cases"].as_array().unwrap() {
        if let Some(why) = unbound(case) {
            skipped.push(format!("{}: {why}", case["query"]));
            continue;
        }
        ran += 1;
        failures.extend(answers(&mut floor, now, case));
    }
    for window in vectors["occurrences"].as_array().unwrap() {
        let (start, end) = (
            window["start"].as_str().unwrap(),
            window["end"].as_str().unwrap(),
        );
        let rows = query(
            &mut floor,
            "OCCURRENCES_BETWEEN",
            &[(":start", start.into()), (":end", end.into())],
        )
        .unwrap();
        let got: Vec<(String, i64, String, String)> = rows
            .iter()
            .map(|row| {
                (
                    text(row, 0).unwrap(),
                    int(row, 1).unwrap(),
                    text(row, 2).unwrap(),
                    text(row, 3).unwrap(),
                )
            })
            .collect();
        let want: Vec<(String, i64, String, String)> = window["rows"]
            .as_array()
            .unwrap()
            .iter()
            .map(|row| {
                (
                    row[0].as_str().unwrap().into(),
                    row[1].as_i64().unwrap(),
                    row[2].as_str().unwrap().into(),
                    row[3].as_str().unwrap().into(),
                )
            })
            .collect();
        if got != want {
            failures.push(format!("occurrences {start}..{end}: {got:?}, not {want:?}"));
        }
    }
    eprintln!(
        "{ran} cases run, {} not binding the shape:\n{}",
        skipped.len(),
        skipped.join("\n")
    );
    assert!(
        failures.is_empty(),
        "{} of {ran} cases fail on SQLite {FLOOR}:\n{}",
        failures.len(),
        failures.join("\n")
    );
}

/// A message with one subject and one body line.
fn message(id: &str, subject: &str, text: &str) -> Vec<u8> {
    format!(
        "Message-ID: <{id}>\r\nSubject: {subject}\r\nFrom: Ann <ann@example.org>\r\n\
         Date: Sat, 01 Aug 2026 12:00:00 +0000\r\n\r\n{text}\r\n"
    )
    .into_bytes()
}

/// Files a message into INBOX as source `local`.
fn file_message(dir: &Path, body: Vec<u8>) {
    let mut owner = PimdirStore::open(dir).unwrap().for_source("local");
    owner.ensure_collection("INBOX", "message/rfc822").unwrap();
    let derived = summary::derive("message/rfc822", &body).unwrap();
    let hash = owner.hash(&body);
    owner
        .write(vec![
            PimdirWriteOp::StoreObject {
                object: PimdirObject {
                    hash: hash.clone(),
                    size: body.len(),
                },
                body: Some(body),
            },
            PimdirWriteOp::UpsertPlacement(placement(
                "INBOX",
                derived.link_id.as_str(),
                derived.link_id.as_str(),
                Some(hash),
                derived.summary.unwrap(),
                derived.sort_key.as_str(),
                PimdirFlags::Known(BTreeSet::new()),
            )),
        ])
        .unwrap();
}

/// The seqs a query hits.
fn seqs(floor: &mut Floor, query: &str) -> Vec<i64> {
    match find::hits(floor, query, Sort::Date, None, 0).unwrap() {
        Answer::Found(hits) => hits.into_iter().map(|hit| hit.seq).collect(),
        Answer::Refused(refused) => panic!("{query:?} refused: {}", refused.message),
    }
}

/// The refresh follows the store: a body's words are found once it is
/// filed, in chunks of the feed, and nothing of a deleted message is left,
/// its text included.
#[test]
fn a_refresh_follows_the_store_in_chunks() {
    let dir = tempfile::tempdir().unwrap();
    file_message(
        dir.path(),
        message("a@x", "Alpha", "the roadmap for Réunion"),
    );
    file_message(dir.path(), message("b@x", "Beta", "a second body"));
    file_message(dir.path(), message("c@x", "Gamma", "a third body"));

    let mut floor = index(dir.path());
    // NOTE: one run per row, then the one finding the feed exhausted.
    assert_eq!(refreshed(&mut floor, dir.path(), 0, 1), 4);
    assert_eq!(seqs(&mut floor, "roadmap").len(), 1);
    assert_eq!(seqs(&mut floor, "reunion"), seqs(&mut floor, "roadmap"));
    assert_eq!(seqs(&mut floor, "body:roa*"), seqs(&mut floor, "roadmap"));
    assert_eq!(seqs(&mut floor, "body").len(), 2);
    assert!(seqs(&mut floor, "subject:roadmap").is_empty());

    PimdirStore::open(dir.path())
        .unwrap()
        .for_source("local")
        .write(vec![PimdirWriteOp::DropPlacement {
            collection: PimdirCollectionId("INBOX".into()),
            handle: PimdirHandle("a@x".into()),
            reason: PimdirDropReason::Deleted,
        }])
        .unwrap();
    refreshed(&mut floor, dir.path(), 0, 100);
    assert!(seqs(&mut floor, "roadmap").is_empty());
    let placements = floor_count(&mut floor, "SELECT count(*) FROM placement");
    assert_eq!(placements, 2);
}

/// One count off the index.
fn floor_count(floor: &mut Floor, sql: &str) -> i64 {
    let rows = floor
        .run(vec![crate::search::sql::Step {
            sql: sql.into(),
            args: Vec::new(),
            read: true,
        }])
        .unwrap();
    int(&rows[0][0], 0).unwrap()
}

/// An index of another shape is refused and left as it is; an older one
/// of this shape, or one built from another store, is rebuilt.
#[test]
fn an_index_of_another_shape_is_refused_and_an_older_one_rebuilt() {
    let dir = tempfile::tempdir().unwrap();
    file_message(dir.path(), message("a@x", "Alpha", "text"));
    let mut floor = index(dir.path());
    refreshed(&mut floor, dir.path(), 0, 10);

    floor.exec("UPDATE index_meta SET shape = 'canonical'");
    assert!(matches!(
        held::held(&mut floor).unwrap(),
        Held::Refused { .. }
    ));
    floor.exec(&format!(
        "UPDATE index_meta SET shape = '{SHAPE}', format_version = 'search-draft-09'"
    ));
    assert!(matches!(
        held::held(&mut floor).unwrap(),
        Held::Refused { .. }
    ));
    floor.exec("UPDATE index_meta SET format_version = 'search-draft-07'");
    assert!(matches!(
        held::held(&mut floor).unwrap(),
        Held::Rebuild { .. }
    ));
    floor.exec("UPDATE index_meta SET format_version = 'search-draft-08', store_id = 'other'");
    assert!(matches!(
        held::held(&mut floor).unwrap(),
        Held::Rebuild { .. }
    ));
    floor.exec("UPDATE index_meta SET store_id = (SELECT store_id FROM store.store_meta)");
    assert_eq!(held::held(&mut floor).unwrap(), Held::Current);
    floor.exec("DROP INDEX message_by_parent");
    assert!(matches!(
        held::held(&mut floor).unwrap(),
        Held::Rebuild { .. }
    ));
}

/// A query the language refuses answers its refusal, never hits.
#[test]
fn a_refused_query_answers_its_refusal() {
    let dir = tempfile::tempdir().unwrap();
    file_message(dir.path(), message("a@x", "Alpha", "text"));
    let mut floor = index(dir.path());
    refreshed(&mut floor, dir.path(), 0, 10);
    let long = "a ".repeat(600);
    for (query, code) in [
        ("re: meeting", "unknown-field"),
        ("\"open", "unterminated-phrase"),
        (long.as_str(), "limit-exceeded"),
    ] {
        match find::hits(&mut floor, query, Sort::Date, None, 0).unwrap() {
            Answer::Refused(refused) => assert_eq!(refused.code, code, "{query:?}"),
            Answer::Found(_) => panic!("{query:?} answered"),
        }
    }
}

/// A hit carries its placements and a snippet cut from its body around a
/// word of the query.
#[test]
fn a_hit_carries_a_snippet_of_its_body() {
    let dir = tempfile::tempdir().unwrap();
    let filler = "word ".repeat(80);
    file_message(
        dir.path(),
        message(
            "a@x",
            "Alpha",
            &format!("{filler} the Roadmap is here {filler}"),
        ),
    );
    let mut floor = index(dir.path());
    refreshed(&mut floor, dir.path(), 0, 10);
    let Answer::Found(page) = find::search(
        &mut floor,
        &dir.path().join("objects"),
        "roadmap",
        Sort::Date,
        10,
        0,
        0,
    )
    .unwrap() else {
        panic!("refused");
    };
    assert!(!page.more);
    let hit = &page.hits[0];
    assert_eq!((hit.kind.as_str(), hit.title.as_str()), ("mail", "Alpha"));
    assert_eq!(hit.placements[0].collection, "INBOX");
    let snippet = hit.snippet.as_deref().unwrap();
    assert!(snippet.contains("the Roadmap is here"), "{snippet}");
    assert!(
        snippet.starts_with('…') && snippet.ends_with('…'),
        "{snippet}"
    );
}

/// A run reading the store leaves its writer free: a deferred run takes no
/// lock of the attached store but its read snapshot, where an immediate
/// one, the platform's own below API 35, takes its write lock.
#[test]
fn a_run_holds_the_store_writer_off_never() {
    let dir = tempfile::tempdir().unwrap();
    file_message(dir.path(), message("a@x", "Alpha", "text"));
    let mut floor = index(dir.path());
    refreshed(&mut floor, dir.path(), 0, 10);
    let mut writer = Floor::open(&dir.path().join("pimdir.db"));
    let write = "UPDATE store_meta SET purges = purges WHERE id = 1";

    for (begin, free) in [
        (served("BEGIN_RUN"), true),
        ("BEGIN IMMEDIATE".into(), false),
    ] {
        floor.exec(&begin);
        query(&mut floor, "LOAD_CHANGE_CURSOR", &[]).unwrap();
        floor.exec("INSERT INTO missing(collection, seq) SELECT collection, seq FROM placement");
        let wrote = writer.run(vec![crate::search::sql::Step {
            sql: write.into(),
            args: Vec::new(),
            read: false,
        }]);
        assert_eq!(wrote.is_ok(), free, "{begin}: {wrote:?}");
        floor.exec("ROLLBACK");
    }
}

/// What index.db weighs per message, on the floor: a thousand messages of
/// about 2 KB of text each, words drawn from a vocabulary of 5,000, indexed
/// whole. Run by hand: `cargo test --lib index_weight -- --ignored
/// --nocapture`.
#[test]
#[ignore]
fn index_weight_per_message() {
    let dir = tempfile::tempdir().unwrap();
    let mut state = 0x2545_f491_4f6c_dd1du64;
    let mut word = || {
        state ^= state << 13;
        state ^= state >> 7;
        state ^= state << 17;
        let n = state % 5_000;
        format!("w{n:x}o{}", n % 7)
    };
    let count = 1_000;
    let mut text_bytes = 0;
    for at in 0..count {
        let body: Vec<String> = (0..300).map(|_| word()).collect();
        let body = body.join(" ");
        text_bytes += body.len();
        let subject: Vec<String> = (0..6).map(|_| word()).collect();
        file_message(
            dir.path(),
            message(&format!("m{at}@x"), &subject.join(" "), &body),
        );
    }
    let mut floor = index(dir.path());
    refreshed(&mut floor, dir.path(), 0, 200);
    drop(floor);
    let size = fs::metadata(dir.path().join("index.db")).unwrap().len();
    let mut floor = index(dir.path());
    let content = floor_count(
        &mut floor,
        "SELECT sum(length(c0title) + length(c1people) + length(c2body) + length(c7from) \
         + length(c11email)) FROM object_text_content",
    );
    eprintln!(
        "{count} messages, {} bytes of body text: index.db {size} bytes, {} per message; \
         the FTS4 content copy holds {content} bytes of it ({} per message)",
        text_bytes,
        size / count as u64,
        content / count as i64,
    );
}
