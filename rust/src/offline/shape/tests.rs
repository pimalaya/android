//! The app's shape against the canonical text: every fork, run side by side
//! with the statement it rewrites on two copies of one store, answers the
//! same rows and leaves the same tables (STORAGE §4.4: a statement's name,
//! parameters, columns and effect bind every implementation). Run on
//! io-pimdir's own SQLite, where both texts parse; the floor build of
//! SQLite 3.18 runs the shape alone (the JVM store tests).

use std::collections::BTreeMap;

use io_pimdir::sql;
use rusqlite::{Connection, types::Value};
use sha2::{Digest, Sha256};

use crate::offline::shape::{APP, SHAPE, statements, uncommented};

/// A body name of STORAGE §5's shape: 52 letters of one kind.
fn hash(letter: char) -> String {
    letter.to_string().repeat(52)
}

/// The store every scenario starts from: the canonical schema, a fixed
/// identity, and rows each fork has something to read or write in.
fn seed() -> String {
    let (a, b, c, d) = (hash('a'), hash('b'), hash('c'), hash('d'));
    format!(
        "INSERT INTO store_meta(id, format, format_version, version, read_version,
                                produce_version, store_id, incarnation, hash_algo,
                                created_at, derivation)
         VALUES (1, 'pimdir', 'storage-draft-08', 1, 1, 1,
                 '0123456789abcdef0123456789abcdef', 1, 'blake3',
                 '2026-11-01T00:00:00.000Z', 3);
         INSERT INTO collections(id, account, kind, name) VALUES
           ('INBOX', 'a', 'message/rfc822', 'INBOX'),
           ('Archive', 'a', 'message/rfc822', 'Archive'),
           ('book', 'a', 'text/vcard', 'book'),
           ('cal', 'a', 'text/calendar', 'cal'),
           ('files', 'a', 'application/octet-stream', 'files');
         INSERT INTO objects(hash, size, refcount) VALUES
           ('{a}', 10, 3), ('{b}', 20, 0), ('{c}', 30, 1), ('{d}', 40, 7);
         INSERT INTO items(collection, link_id, seq, flags_known, object_hash, sort_key, level,
                           deleted, retained_at, retained_by)
         VALUES
           ('INBOX', 'm1', 1, 1, '{a}', '2026-01-01T00:00:00Z', 2, 0, NULL, NULL),
           ('INBOX', 'm2', 2, 0, '{b}', '2026-03-01T00:00:00Z', 2, 0, NULL, NULL),
           ('INBOX', 'r1', 3, 0, '{c}', '2025-01-01T00:00:00Z', 2, 1,
            '2020-01-01T00:00:00Z', 'imap'),
           ('INBOX', 'u1', 7, 0, '{d}', '', 2, 0, NULL, NULL),
           ('INBOX', '007', 8, 0, NULL, '', 1, 0, NULL, NULL),
           ('INBOX', '7', 9, 0, NULL, '', 1, 0, NULL, NULL),
           ('Archive', 'm1', 1, 0, '{a}', '2026-01-01T00:00:00Z', 2, 0, NULL, NULL),
           ('book', 'c1', 4, 0, '{b}', 'x', 2, 0, NULL, NULL),
           ('cal', 'e1', 5, 0, '{c}', '2026-02-01T00:00:00Z', 2, 0, NULL, NULL),
           ('cal', 't1', 6, 0, NULL, '', 1, 0, NULL, NULL),
           ('cal', 'j1', 10, 0, NULL, '', 1, 0, NULL, NULL),
           ('files', 'f1', 11, 0, NULL, '', 1, 0, NULL, NULL);
         INSERT INTO placement_flag(collection, link_id, flag) VALUES ('INBOX', 'm1', '\\Seen');
         INSERT INTO bindings(collection, link_id, source, handle, base_flags_known, base_object,
                              base_revision, base_present, shared_object)
         VALUES
           ('INBOX', 'm1', 'imap', '10', 1, '{a}', NULL, 1, '{a}'),
           ('INBOX', 'm2', 'imap', '11', 0, '{b}', NULL, 1, NULL),
           ('Archive', 'm1', 'imap', '20', 0, '{a}', NULL, 1, NULL),
           ('book', 'c1', 'server', 'c1.vcf', 0, '{b}', 'e1', 1, NULL),
           ('cal', 'e1', 'server', 'e1.ics', 0, '{c}', 'e2', 1, NULL);
         INSERT INTO binding_base_flag(collection, link_id, source, flag)
         VALUES ('INBOX', 'm1', 'imap', '\\Seen');
         INSERT INTO mail_summary(collection, link_id, message_id, subject, sender, date,
                                  invitation)
         VALUES
           ('INBOX', 'm1', 'm1', 'one', 'x@example.org', '2026-01-01T00:00:00Z', NULL),
           ('INBOX', 'm2', 'm2', 'two', 'y@example.org', '2026-03-01T00:00:00Z', 'e1'),
           ('Archive', 'm1', 'm1', 'one', 'x@example.org', '2026-01-01T00:00:00Z', NULL);
         INSERT INTO item_address(collection, link_id, role, position, address)
         VALUES ('INBOX', 'm1', 'from', 0, 'x@example.org'),
                ('INBOX', 'm2', 'from', 0, 'y@example.org'),
                ('book', 'c1', 'email', 0, 'x@example.org'),
                ('book', 'c1', 'email', 1, 'y@example.org');
         INSERT INTO task_summary(collection, link_id, summary, parent, mid)
         VALUES ('cal', 't1', 'task', 'e1', 'm2');
         INSERT INTO sources(collection, source, checkpoint) VALUES ('INBOX', 'imap', x'00');
         INSERT INTO capabilities(account, source, collection, capability, support)
         VALUES ('a', 'imap', NULL, 'x-move', 'full');
         INSERT INTO performers(account, capability, source) VALUES (NULL, 'x-send', 'smtp');
         INSERT INTO supersessions(collection, link_id, by_collection, by_link_id)
         VALUES ('INBOX', 'm2', 'Archive', 'm1');
         INSERT INTO queue(created_at, producer, collection, action, payload, object_hash)
         VALUES ('2026-11-01T00:00:00Z', 'p', 'INBOX', 'add', '{{}}', '{d}');
         INSERT INTO item_reference(from_kind, from_link_id, to_kind, to_link_id, role, origin,
                                    created_at)
         VALUES ('message/rfc822', 'm1', 'text/vcard', 'c1', 'sender', 'auto',
                 '2026-11-01T00:00:00.000Z');"
    )
}

/// Which text a store runs: the canonical one, the shape's, or a naive
/// rewrite of one statement standing for a fork, to show a scenario tells
/// it apart.
#[derive(Clone, Copy)]
enum Text<'a> {
    Canonical,
    Shape,
    Naive(&'a str, &'a str),
}

impl Text<'_> {
    fn of(self, name: &str) -> String {
        match self {
            Text::Naive(naive, text) if naive == name => text.to_owned(),
            Text::Canonical | Text::Naive(..) => {
                let canonical: BTreeMap<_, _> = sql::all().collect();
                canonical[name].to_owned()
            }
            Text::Shape => statements()[name].to_owned(),
        }
    }
}

/// A fresh store under `text`: the canonical schema, the list table that
/// text creates, and the seed.
fn store(text: Text) -> Connection {
    let db = Connection::open_in_memory().unwrap();
    db.execute_batch("PRAGMA foreign_keys = ON;").unwrap();
    for migration in sql::MIGRATIONS {
        db.execute_batch(migration).unwrap();
    }
    db.execute_batch(&text.of("CREATE_PARAM")).unwrap();
    db.execute_batch(&seed()).unwrap();
    db
}

/// One call of a scenario: a statement's name, its values and its lists.
struct Call<'a> {
    name: &'a str,
    values: Vec<(&'a str, Value)>,
    lists: Vec<(&'a str, Vec<Value>)>,
}

fn call<'a>(name: &'a str, values: Vec<(&'a str, Value)>) -> Call<'a> {
    Call {
        name,
        values,
        lists: Vec::new(),
    }
}

fn text(value: &str) -> Value {
    Value::Text(value.to_owned())
}

/// The statements of a text, comments out, split where each ends.
fn split(text: &str) -> Vec<String> {
    uncommented(text)
        .split_inclusive(";\n")
        .map(str::trim)
        .filter(|piece| !piece.is_empty())
        .map(str::to_owned)
        .collect()
}

/// Runs one call in one transaction, as the Java owner does: its lists
/// bound through `temp.param`, then each statement in order, every value it
/// names bound. Answers the rows its statements read, in the order read,
/// or the error that rolled it back.
fn run(db: &Connection, text: Text, call: &Call) -> Result<Vec<String>, String> {
    db.execute_batch("BEGIN IMMEDIATE;").unwrap();
    let ran = (|| -> Result<Vec<String>, rusqlite::Error> {
        db.execute("DELETE FROM temp.param", [])?;
        for (slot, values) in &call.lists {
            for (position, value) in values.iter().enumerate() {
                db.execute(
                    "INSERT INTO temp.param(slot, position, value) VALUES (?1, ?2, ?3)",
                    (slot, position as i64, value),
                )?;
            }
        }
        let mut rows = Vec::new();
        for piece in split(&text.of(call.name)) {
            let mut statement = db.prepare(&piece)?;
            for index in 1..=statement.parameter_count() {
                let name = statement.parameter_name(index).unwrap().to_owned();
                let value = call
                    .values
                    .iter()
                    .find(|(param, _)| *param == &name[1..])
                    .map(|(_, value)| value.clone())
                    .unwrap_or(Value::Null);
                statement.raw_bind_parameter(index, value)?;
            }
            let columns = statement.column_count();
            let mut read = statement.raw_query();
            while let Some(row) = read.next()? {
                let mut cells = Vec::new();
                for index in 0..columns {
                    cells.push(cell(row.get::<_, Value>(index)?));
                }
                rows.push(cells.join("|"));
            }
        }
        Ok(rows)
    })();
    match ran {
        Ok(rows) => {
            db.execute_batch("COMMIT;").unwrap();
            Ok(rows)
        }
        Err(err) => {
            db.execute_batch("ROLLBACK;").unwrap();
            Err(err.to_string())
        }
    }
}

/// One value as the comparison reads it, an instant SQLite stamped masked:
/// two runs never share its milliseconds.
fn cell(value: Value) -> String {
    match value {
        Value::Null => "NULL".to_owned(),
        Value::Integer(int) => format!("i{int}"),
        Value::Real(real) => format!("r{real}"),
        Value::Text(text) if text.starts_with("20") && text.ends_with('Z') && text.len() == 24 => {
            "<now>".to_owned()
        }
        Value::Text(text) => format!("t{text}"),
        Value::Blob(blob) => format!("b{blob:?}"),
    }
}

/// Every row of every table of the store, each table's rows sorted.
fn dump(db: &Connection) -> BTreeMap<String, Vec<String>> {
    let mut tables = db
        .prepare("SELECT name FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%'")
        .unwrap();
    let names: Vec<String> = tables
        .query_map([], |row| row.get(0))
        .unwrap()
        .map(Result::unwrap)
        .collect();
    let mut dump = BTreeMap::new();
    for name in names {
        let mut read = db.prepare(&format!("SELECT * FROM \"{name}\"")).unwrap();
        let columns = read.column_count();
        let mut rows: Vec<String> = read
            .query_map([], |row| {
                Ok((0..columns)
                    .map(|index| cell(row.get::<_, Value>(index).unwrap()))
                    .collect::<Vec<_>>()
                    .join("|"))
            })
            .unwrap()
            .map(Result::unwrap)
            .collect();
        rows.sort();
        dump.insert(name, rows);
    }
    dump
}

/// What a scenario left: each call's rows (sorted, a `RETURNING` naming no
/// order) or whether it failed, and the store's tables.
type Outcome = (Vec<Result<Vec<String>, ()>>, BTreeMap<String, Vec<String>>);

fn outcome(text: Text, calls: &[Call]) -> Outcome {
    let db = store(text);
    let answers = calls
        .iter()
        .map(|call| {
            run(&db, text, call)
                .map(|mut rows| {
                    rows.sort();
                    rows
                })
                .map_err(|_| ())
        })
        .collect();
    (answers, dump(&db))
}

/// The shape answers and leaves what the canonical text does.
fn same(label: &str, calls: &[Call]) {
    let canonical = outcome(Text::Canonical, calls);
    let shape = outcome(Text::Shape, calls);
    // NOTE: a scenario every call of which fails, or which neither reads
    // nor writes anything, would agree with any text.
    let read = canonical
        .0
        .iter()
        .any(|answer| answer.as_ref().is_ok_and(|rows| !rows.is_empty()));
    assert!(
        canonical.0.iter().any(Result::is_ok)
            && (read || canonical.1 != dump(&store(Text::Canonical))),
        "{label}: the scenario does nothing: {:?}",
        canonical.0,
    );
    assert_eq!(canonical.0, shape.0, "{label}: the answers differ");
    for (table, rows) in &canonical.1 {
        assert_eq!(rows, &shape.1[table], "{label}: {table} differs");
    }
}

/// A naive rewrite of `name` answers or leaves something else than the
/// canonical text in the scenario, which the shape's text does not.
fn tells_apart(label: &str, name: &str, naive: &str, calls: &[Call]) {
    same(label, calls);
    let canonical = outcome(Text::Canonical, calls);
    let naive = outcome(Text::Naive(name, naive), calls);
    assert!(
        canonical != naive,
        "{label}: the naive rewrite of {name} passes for the canonical text"
    );
}

/// `-- Fork of <name> (sha256 <hex>)`: the canonical text a fork names.
fn forked_digest(text: &str) -> Option<&str> {
    let start = text.find("(sha256 ")? + "(sha256 ".len();
    let end = start + text[start..].find(')')?;
    Some(&text[start..end])
}

#[test]
fn every_fork_names_the_canonical_text_it_rewrites() {
    let canonical: BTreeMap<_, _> = sql::all().collect();
    for (name, profile, text) in SHAPE.iter().filter(|(_, profile, _)| *profile != APP) {
        let Some(original) = canonical.get(name) else {
            panic!("{profile}/{name} forks no canonical statement");
        };
        let digest: String = Sha256::digest(original.as_bytes())
            .iter()
            .map(|byte| format!("{byte:02x}"))
            .collect();
        assert_eq!(
            forked_digest(text),
            Some(&digest[..16]),
            "{profile}/{name} was forked from another canonical text: read the \
             canonical one again, then restate its digest",
        );
    }
}

#[test]
fn no_app_statement_shadows_a_canonical_one() {
    let canonical: BTreeMap<_, _> = sql::all().collect();
    for (name, _, _) in SHAPE.iter().filter(|(_, profile, _)| *profile == APP) {
        assert!(
            !canonical.contains_key(name),
            "app/{name} is a canonical statement's name"
        );
    }
}

#[test]
fn every_fork_is_run_beside_its_canonical_statement() {
    let source = include_str!("tests.rs");
    for (name, profile, _) in SHAPE.iter().filter(|(_, profile, _)| *profile != APP) {
        assert!(
            source.contains(&format!("\"{name}\"")),
            "{profile}/{name} is run by no scenario here"
        );
    }
}

#[test]
fn a_list_value_keeps_its_type() {
    // NOTE: `value ANY` outside a STRICT table has NUMERIC affinity, which
    // stores '007' as 7 and matches the item '7' too.
    tells_apart(
        "a link id of digits",
        "CREATE_PARAM",
        "CREATE TEMP TABLE IF NOT EXISTS param (slot TEXT NOT NULL, \
         position INTEGER NOT NULL, value ANY, PRIMARY KEY (slot, position));",
        &[Call {
            name: "LOAD_ITEMS_BY_LINK",
            values: vec![("collection", text("INBOX"))],
            lists: vec![("links", vec![text("007")])],
        }],
    );
}

#[test]
fn an_upsert_inserts_then_updates() {
    let summary = |invitation: Value| {
        call(
            "UPSERT_MAIL_SUMMARY",
            vec![
                ("collection", text("INBOX")),
                ("link_id", text("u1")),
                ("subject", text("three")),
                ("size", Value::Integer(3)),
                ("invitation", invitation),
            ],
        )
    };
    same(
        "a mail summary written, then restated with no invitation",
        &[summary(text("e1")), summary(Value::Null)],
    );
    same(
        "a mail summary over the seeded one",
        &[call(
            "UPSERT_MAIL_SUMMARY",
            vec![
                ("collection", text("INBOX")),
                ("link_id", text("m2")),
                ("subject", text("re")),
            ],
        )],
    );
    for (name, link) in [
        ("UPSERT_CONTACT_SUMMARY", ("book", "c1")),
        ("UPSERT_EVENT_SUMMARY", ("cal", "e1")),
        ("UPSERT_TASK_SUMMARY", ("cal", "t1")),
        ("UPSERT_JOURNAL_SUMMARY", ("cal", "j1")),
        ("UPSERT_FILE_SUMMARY", ("files", "f1")),
    ] {
        let write = |label: &str| {
            call(
                name,
                vec![
                    ("collection", text(link.0)),
                    ("link_id", text(link.1)),
                    ("uid", text(label)),
                    ("fn", text(label)),
                    ("summary", text(label)),
                    ("name", text(label)),
                    ("media_type", text("text/plain")),
                    ("size", Value::Integer(1)),
                    ("recurring", Value::Integer(0)),
                ],
            )
        };
        same(name, &[write("first"), write("second")]);
    }
}

#[test]
fn an_opened_round_is_drawn_once() {
    // NOTE: an insert ignored, then the update, draws round 2 for a source
    // the store had no row of.
    let open = |collection: &str| {
        call(
            "OPEN_ROUND",
            vec![
                ("collection", text(collection)),
                ("source", text("imap")),
                ("since", text("2026-01-01T00:00:00Z")),
                ("band", Value::Integer(1)),
            ],
        )
    };
    tells_apart(
        "a round opened on a new source, then on a known one",
        "OPEN_ROUND",
        "INSERT OR IGNORE INTO sources(collection, source, round, round_since, round_until, \
         round_started_at, round_band) VALUES(:collection, :source, 1, :since, :until, \
         strftime('%Y-%m-%dT%H:%M:%fZ','now'), :band);\n\
         UPDATE sources SET round = round + 1, round_since = :since, round_until = :until, \
         round_cursor = NULL, round_checkpoint = NULL, \
         round_started_at = strftime('%Y-%m-%dT%H:%M:%fZ','now'), round_band = :band \
         WHERE collection = :collection AND source = :source;\n",
        &[open("Archive"), open("INBOX"), open("INBOX")],
    );
}

#[test]
fn every_upsert_of_a_row_by_key_agrees() {
    let checkpoint = |collection: &str| {
        call(
            "UPSERT_CHECKPOINT",
            vec![
                ("collection", text(collection)),
                ("source", text("imap")),
                ("checkpoint", Value::Blob(vec![1, 2])),
            ],
        )
    };
    same("checkpoints", &[checkpoint("INBOX"), checkpoint("Archive")]);

    let object = |letter: char, size: i64| {
        call(
            "STORE_OBJECT",
            vec![
                ("hash", text(&hash(letter))),
                ("size", Value::Integer(size)),
            ],
        )
    };
    same("objects", &[object('e', 5), object('a', 99)]);

    let ensure = |collection: &str, account: &str| {
        call(
            "ENSURE_COLLECTION",
            vec![("collection", text(collection)), ("account", text(account))],
        )
    };
    same(
        "ensured collections",
        &[ensure("X", "a"), ensure("INBOX", "b")],
    );

    for name in ["SET_COLLECTION_KIND", "SET_COLLECTION_NAME"] {
        let set = |collection: &str| {
            call(
                name,
                vec![
                    ("collection", text(collection)),
                    ("account", text("a")),
                    ("kind", text("text/calendar")),
                    ("name", text("named")),
                ],
            )
        };
        same(name, &[set("cal"), set("new")]);
    }

    let capability = |collection: Value, support: &str| {
        call(
            "SET_CAPABILITY",
            vec![
                ("account", text("a")),
                ("source", text("imap")),
                ("collection", collection),
                ("capability", text("x-move")),
                ("support", text(support)),
            ],
        )
    };
    same(
        "capabilities",
        &[
            capability(Value::Null, "none"),
            capability(text("INBOX"), "partial"),
            capability(text("INBOX"), "full"),
        ],
    );

    let performer = |account: Value| {
        call(
            "SET_PERFORMER",
            vec![
                ("account", account),
                ("capability", text("x-send")),
                ("source", text("jmap")),
            ],
        )
    };
    same(
        "performers",
        &[performer(Value::Null), performer(text("a"))],
    );

    let supersede = |link: &str| {
        call(
            "SUPERSEDE_ITEM",
            vec![
                ("collection", text("INBOX")),
                ("link_id", text(link)),
                ("by_collection", text("Archive")),
                ("by_link_id", text("m1")),
            ],
        )
    };
    same("supersessions", &[supersede("m2"), supersede("u1")]);
}

#[test]
fn a_flag_insert_skips_what_is_held_and_raises_what_is_malformed() {
    let flags = |name: &'static str, values: Vec<&str>| Call {
        name,
        values: vec![
            ("collection", text("INBOX")),
            ("link_id", text("m1")),
            ("source", text("imap")),
        ],
        lists: vec![("flags", values.into_iter().map(text).collect())],
    };
    // NOTE: `INSERT OR IGNORE` drops a flag its CHECK refuses where the
    // canonical text raises.
    tells_apart(
        "flags held, named twice, then malformed",
        "ADD_FLAGS",
        "INSERT OR IGNORE INTO placement_flag(collection, link_id, flag) \
         SELECT :collection, :link_id, value FROM temp.param WHERE slot = 'flags';",
        &[
            flags("ADD_FLAGS", vec!["\\Seen", "\\Flagged", "\\Flagged"]),
            flags("ADD_FLAGS", vec!["\\Draft", "no space"]),
        ],
    );
    same(
        "base flags held, named twice, then malformed",
        &[
            flags("ADD_BASE_FLAGS", vec!["\\Seen", "\\Flagged", "\\Flagged"]),
            flags("ADD_BASE_FLAGS", vec!["\\Draft", "no space"]),
        ],
    );
}

#[test]
fn every_reference_rule_records_what_it_lacks() {
    for name in [
        "LINK_SENDERS_OF",
        "LINK_MAIL_FROM",
        "LINK_PARENTS_OF",
        "LINK_PARENTS_TO",
        "LINK_SOURCES_OF",
        "LINK_SOURCES_TO",
        "LINK_INVITATIONS_OF",
        "LINK_INVITATIONS_TO",
    ] {
        same(
            name,
            &[
                call(name, vec![("link_id", Value::Null)]),
                call(name, vec![("link_id", Value::Null)]),
                call(name, vec![("link_id", text("m1"))]),
                call(name, vec![("link_id", text("e1"))]),
            ],
        );
    }
}

#[test]
fn a_reference_added_or_removed_answers_its_row() {
    let reference = |from: (&str, &str), to: (&str, &str), role: &str, origin: &str| {
        vec![
            ("from_kind", text(from.0)),
            ("from_link_id", text(from.1)),
            ("to_kind", text(to.0)),
            ("to_link_id", text(to.1)),
            ("role", text(role)),
            ("origin", text(origin)),
        ]
    };
    let (mail, card) = ("message/rfc822", "text/vcard");
    same(
        "references added",
        &[
            call(
                "ADD_REFERENCE",
                reference((mail, "m2"), (card, "c1"), "related", "user"),
            ),
            call(
                "ADD_REFERENCE",
                reference((mail, "m1"), (card, "c1"), "sender", "auto"),
            ),
            call(
                "ADD_REFERENCE",
                reference((mail, "m1"), (card, "c1"), "sender", "user"),
            ),
            call(
                "ADD_REFERENCE",
                reference((mail, "m1"), (card, "c1"), "sender", "auto"),
            ),
            call(
                "ADD_REFERENCE",
                reference((mail, "nope"), (card, "c1"), "related", "user"),
            ),
            call(
                "ADD_REFERENCE",
                reference((mail, "alt:x"), (card, "c1"), "related", "user"),
            ),
        ],
    );
    same(
        "references removed",
        &[
            call(
                "REMOVE_REFERENCE",
                reference((mail, "m1"), (card, "c1"), "sender", "auto"),
            ),
            call(
                "REMOVE_REFERENCE",
                reference((mail, "m1"), (card, "c1"), "sender", "auto"),
            ),
        ],
    );
}

#[test]
fn a_returning_write_answers_what_it_wrote() {
    same(
        "counters",
        &[
            call("BUMP_NEXT_SEQ", vec![]),
            call("BUMP_NEXT_SEQ", vec![]),
            call("BUMP_GENERATION", vec![("collection", text("INBOX"))]),
            call("BUMP_GENERATION", vec![("collection", text("nope"))]),
        ],
    );
    for name in ["CANCEL_ACTION", "CLAIM_ACTION"] {
        same(
            name,
            &[
                call(name, vec![("id", Value::Integer(1))]),
                call(name, vec![("id", Value::Integer(1))]),
            ],
        );
    }
    same(
        "purges",
        &[
            call(
                "PURGE_ITEM",
                vec![("collection", text("INBOX")), ("seq", Value::Integer(1))],
            ),
            call(
                "PURGE_ITEM",
                vec![("collection", text("INBOX")), ("seq", Value::Integer(3))],
            ),
        ],
    );
    same(
        "a purge below a cutoff",
        &[call(
            "PURGE_RETAINED_BEFORE",
            vec![("cutoff", text("2021-01-01T00:00:00Z"))],
        )],
    );
    same(
        "unbound items deleted",
        &[
            call(
                "DELETE_UNBOUND_ITEM",
                vec![("collection", text("INBOX")), ("link_id", text("m1"))],
            ),
            call(
                "DELETE_UNBOUND_ITEM",
                vec![("collection", text("INBOX")), ("link_id", text("u1"))],
            ),
        ],
    );
    for before in ["2026-02-01T00:00:00Z", "2027-01-01T00:00:00Z"] {
        same(
            "a collection below a date",
            &[call(
                "COLLECT_BEFORE",
                vec![("collection", text("INBOX")), ("before", text(before))],
            )],
        );
    }
}

#[test]
fn a_release_below_a_date_lets_go_of_the_same_bodies() {
    for until in [Value::Text("2026-02-01T00:00:00Z".to_owned()), Value::Null] {
        let release = |name: &'static str| Call {
            name,
            values: vec![("until", until.clone())],
            lists: vec![("collections", vec![text("INBOX"), text("Archive")])],
        };
        same(
            "bases, then bodies, then the recount",
            &[
                release("RELEASE_BASES_BEFORE"),
                release("RELEASE_BEFORE"),
                call("RECOMPUTE_REFCOUNTS", vec![]),
            ],
        );
    }
}

#[test]
fn a_recount_settles_every_drift() {
    same("the seed's drift", &[call("RECOMPUTE_REFCOUNTS", vec![])]);
}

#[test]
fn a_recount_seeks_each_pointer() {
    // NOTE: one correlated count over the six pointers' union would scan
    // them per object; each count is a seek on its pointer's index.
    let db = store(Text::Shape);
    let recount = split(statements()["RECOMPUTE_REFCOUNTS"]).remove(0);
    let mut plan = db
        .prepare(&format!("EXPLAIN QUERY PLAN {recount}"))
        .unwrap();
    let steps: Vec<String> = plan
        .query_map([], |row| row.get::<_, String>(3))
        .unwrap()
        .map(Result::unwrap)
        .collect();
    for step in &steps {
        assert!(
            !step.starts_with("SCAN") || step.contains("objects"),
            "the recount scans: {steps:#?}"
        );
    }
}

#[test]
fn the_schema_is_the_canonical_one() {
    // NOTE: one on-disk format (STORAGE §4.1, §6): the shape forks
    // statements, never the schema, so a store the canonical text wrote
    // opens as the same store.
    assert_eq!(crate::offline::shape::migrations(), sql::MIGRATIONS);
}
