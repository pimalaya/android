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
         VALUES (1, 'pimdir', 'storage-draft-17', 1, 1, 1,
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
         INSERT INTO items(collection, link_id, seq, flags_known, object, sort_key, level,
                           deleted, retained_at, retained_by)
         VALUES
           ('INBOX', 'm1', 1, 1, (SELECT id FROM objects WHERE hash = '{a}'), '2026-01-01T00:00:00Z', 2, 0, NULL, NULL),
           ('INBOX', 'm2', 2, 0, (SELECT id FROM objects WHERE hash = '{b}'), '2026-03-01T00:00:00Z', 2, 0, NULL, NULL),
           ('INBOX', 'r1', 3, 0, (SELECT id FROM objects WHERE hash = '{c}'), '2025-01-01T00:00:00Z', 2, 1,
            '2020-01-01T00:00:00.000Z', 'imap'),
           ('INBOX', 'u1', 7, 0, (SELECT id FROM objects WHERE hash = '{d}'), '', 2, 0, NULL, NULL),
           ('INBOX', '007', 8, 0, NULL, '', 1, 0, NULL, NULL),
           ('INBOX', '7', 9, 0, NULL, '', 1, 0, NULL, NULL),
           ('Archive', 'm1', 1, 0, (SELECT id FROM objects WHERE hash = '{a}'), '2026-01-01T00:00:00Z', 2, 0, NULL, NULL),
           ('book', 'c1', 4, 0, (SELECT id FROM objects WHERE hash = '{b}'), 'x', 2, 0, NULL, NULL),
           ('cal', 'e1', 5, 0, (SELECT id FROM objects WHERE hash = '{c}'), '2026-02-01T00:00:00Z', 2, 0, NULL, NULL),
           ('cal', 't1', 6, 0, NULL, '', 1, 0, NULL, NULL),
           ('cal', 'j1', 10, 0, NULL, '', 1, 0, NULL, NULL),
           ('files', 'f1', 11, 0, NULL, '', 1, 0, NULL, NULL);
         UPDATE items SET marks = 1, keywords = '$a'
         WHERE collection = 'INBOX' AND link_id = 'm1';
         INSERT INTO bindings(item, collection, source, handle, base_flags_known, base_object,
                              base_revision, base_present, shared_object)
         VALUES
           ((SELECT id FROM items WHERE collection = 'INBOX' AND link_id = 'm1'), 'INBOX', 'imap', '10', 1, (SELECT id FROM objects WHERE hash = '{a}'), NULL, 1, (SELECT id FROM objects WHERE hash = '{a}')),
           ((SELECT id FROM items WHERE collection = 'INBOX' AND link_id = 'm2'), 'INBOX', 'imap', '11', 0, (SELECT id FROM objects WHERE hash = '{b}'), NULL, 1, NULL),
           ((SELECT id FROM items WHERE collection = 'Archive' AND link_id = 'm1'), 'Archive', 'imap', '20', 0, (SELECT id FROM objects WHERE hash = '{a}'), NULL, 1, NULL),
           ((SELECT id FROM items WHERE collection = 'book' AND link_id = 'c1'), 'book', 'server', 'c1.vcf', 0, (SELECT id FROM objects WHERE hash = '{b}'), 'e1', 1, NULL),
           ((SELECT id FROM items WHERE collection = 'cal' AND link_id = 'e1'), 'cal', 'server', 'e1.ics', 0, (SELECT id FROM objects WHERE hash = '{c}'), 'e2', 1, NULL);
         UPDATE bindings SET base_marks = 1, base_keywords = '$a'
         WHERE collection = 'INBOX' AND handle = '10';
         INSERT INTO summary(item, component, uid, title, sender, date, invitation)
         VALUES
           ((SELECT id FROM items WHERE collection = 'INBOX' AND link_id = 'm1'), 'mail', 'm1', 'one', 'x@example.org', '2026-01-01T00:00:00Z', NULL),
           ((SELECT id FROM items WHERE collection = 'INBOX' AND link_id = 'm2'), 'mail', 'm2', 'two', 'y@example.org', '2026-03-01T00:00:00Z', 'e1'),
           ((SELECT id FROM items WHERE collection = 'Archive' AND link_id = 'm1'), 'mail', 'm1', 'one', 'x@example.org', '2026-01-01T00:00:00Z', NULL);
         INSERT INTO summary_value(item, role, position, value)
         VALUES ((SELECT id FROM items WHERE collection = 'INBOX' AND link_id = 'm1'), 'from', 0, 'x@example.org'),
                ((SELECT id FROM items WHERE collection = 'INBOX' AND link_id = 'm2'), 'from', 0, 'y@example.org'),
                ((SELECT id FROM items WHERE collection = 'book' AND link_id = 'c1'), 'email', 0, 'x@example.org'),
                ((SELECT id FROM items WHERE collection = 'book' AND link_id = 'c1'), 'email', 1, 'y@example.org');
         INSERT INTO summary(item, component, title, parent, mid)
         VALUES ((SELECT id FROM items WHERE collection = 'cal' AND link_id = 't1'), 'task', 'task', 'e1', 'm2');
         INSERT INTO sources(collection, source, checkpoint) VALUES ('INBOX', 'imap', x'00');
         INSERT INTO capabilities(account, source, collection, capability, support)
         VALUES ('a', 'imap', NULL, 'x-move', 'full');
         INSERT INTO performers(account, capability, source) VALUES (NULL, 'x-send', 'smtp');
         INSERT INTO supersessions(item, by_item)
         VALUES ((SELECT id FROM items WHERE collection = 'INBOX' AND link_id = 'm2'),
                 (SELECT id FROM items WHERE collection = 'Archive' AND link_id = 'm1'));
         INSERT INTO queue(created_at, producer, collection, action, payload, object)
         VALUES ('2026-11-01T00:00:00.000Z', 'p', 'INBOX', 'add', '{{}}', (SELECT id FROM objects WHERE hash = '{d}')),
                ('2026-11-01T00:00:01.000Z', 'p', 'INBOX', 'submit', '{{}}', NULL);
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
            "UPSERT_SUMMARY",
            vec![
                ("collection", text("INBOX")),
                ("link_id", text("u1")),
                ("component", text("mail")),
                ("title", text("three")),
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
            "UPSERT_SUMMARY",
            vec![
                ("collection", text("INBOX")),
                ("link_id", text("m2")),
                ("component", text("mail")),
                ("title", text("re")),
            ],
        )],
    );
    for (component, link) in [
        ("contact", ("book", "c1")),
        ("event", ("cal", "e1")),
        ("task", ("cal", "t1")),
        ("journal", ("cal", "j1")),
        ("file", ("files", "f1")),
    ] {
        let write = |label: &str| {
            let mut values = vec![
                ("collection", text(link.0)),
                ("link_id", text(link.1)),
                ("component", text(component)),
                ("title", text(label)),
            ];
            match component {
                "file" => {
                    values.push(("media_type", text("text/plain")));
                    values.push(("size", Value::Integer(1)));
                }
                "event" | "task" => {
                    values.push(("uid", text(label)));
                    values.push(("recurring", Value::Integer(0)));
                }
                _ => values.push(("uid", text(label))),
            }
            call("UPSERT_SUMMARY", values)
        };
        same(component, &[write("first"), write("second")]);
    }
    let restated = |component: &str| {
        call(
            "UPSERT_SUMMARY",
            vec![
                ("collection", text("cal")),
                ("link_id", text("e1")),
                ("component", text(component)),
                ("title", text(component)),
            ],
        )
    };
    same(
        "a calendar resource rewritten as another component",
        &[restated("event"), restated("task"), restated("journal")],
    );
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
fn a_property_insert_skips_what_is_held_and_raises_what_is_malformed() {
    let property = |key: &str, value: &str| {
        call(
            "ADD_PROPERTY",
            vec![
                ("seq", Value::Integer(1)),
                ("key", text(key)),
                ("value", text(value)),
            ],
        )
    };
    tells_apart(
        "a value held, added twice, then a key past its bounds",
        "ADD_PROPERTY",
        "INSERT OR IGNORE INTO item_property(seq, key, value) VALUES (:seq, :key, :value);",
        &[
            property("pimdir/label", "Work"),
            property("pimdir/label", "Work"),
            property("Not A Key", "Work"),
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
    same(
        "a parked row put back, once",
        &[
            call("RETRY_ACTION", vec![("id", Value::Integer(1))]),
            call(
                "PARK_ACTION",
                vec![("id", Value::Integer(1)), ("error", text("refused"))],
            ),
            call("RETRY_ACTION", vec![("id", Value::Integer(1))]),
            call("RETRY_ACTION", vec![("id", Value::Integer(1))]),
            call("RETRY_ACTION", vec![("id", Value::Integer(9))]),
        ],
    );
    let unconfirm = |id: i64, detail: Value| {
        call(
            "UNCONFIRM_ACTION",
            vec![("id", Value::Integer(id)), ("detail", detail)],
        )
    };
    // NOTE: an update of the row whatever its action would park the `add`
    // the owner applies itself, which the canonical text leaves pending.
    tells_apart(
        "an intent parked as unconfirmed, an owner's action and no row left alone",
        "UNCONFIRM_ACTION",
        "SELECT id FROM queue WHERE id = :id;\n\
         UPDATE queue SET attempts = attempts + 1, \
         error = 'unconfirmed: ' || coalesce(:detail, '') WHERE id = :id;\n",
        &[
            unconfirm(2, text("no reply")),
            unconfirm(1, text("no reply")),
            unconfirm(9, text("no reply")),
            unconfirm(2, Value::Null),
            call(
                "PARK_ACTION",
                vec![
                    ("id", Value::Integer(2)),
                    ("error", text("unconfirmed: relayed")),
                ],
            ),
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
            vec![("before", text("2021-01-01T00:00:00.000Z"))],
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
            "an eviction below a date",
            &[call(
                "EVICT_BEFORE",
                vec![("collection", text("INBOX")), ("before", text(before))],
            )],
        );
    }
}

#[test]
fn a_coverage_rolls_up_as_the_canonical_text_does() {
    let collections = || vec![("collections", vec![text("INBOX"), text("Archive")])];
    let roll = || Call {
        name: "ROLL_COVERAGE",
        values: Vec::new(),
        lists: collections(),
    };
    let checkpoint = call(
        "UPSERT_CHECKPOINT",
        vec![
            ("collection", text("Archive")),
            ("source", text("imap")),
            ("checkpoint", Value::Blob(vec![1])),
        ],
    );
    let open = call(
        "OPEN_ROUND",
        vec![
            ("collection", text("INBOX")),
            ("source", text("imap")),
            ("since", text("2026-01-01T00:00:00Z")),
            ("until", Value::Null),
            ("band", Value::Integer(0)),
        ],
    );
    let close = call(
        "CLOSE_ROUND",
        vec![
            ("collection", text("INBOX")),
            ("source", text("imap")),
            ("since", text("2026-01-01T00:00:00Z")),
            ("until", text("2026-06-01T00:00:00Z")),
            ("checkpoint", Value::Null),
        ],
    );
    same(
        "one source never covered, then one covered beside another not",
        &[roll(), checkpoint, open, close, roll()],
    );
}

#[test]
fn a_release_below_a_date_lets_go_of_the_same_bodies() {
    for before in [Value::Text("2026-02-01T00:00:00Z".to_owned()), Value::Null] {
        let release = |name: &'static str| Call {
            name,
            values: vec![("before", before.clone())],
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

/// One read of the shape as the Java owner binds it: each row's cells by
/// their column's name.
fn read_named(db: &Connection, call: &Call) -> Vec<Vec<(String, Value)>> {
    db.execute("DELETE FROM temp.param", []).unwrap();
    for (slot, values) in &call.lists {
        for (position, value) in values.iter().enumerate() {
            db.execute(
                "INSERT INTO temp.param(slot, position, value) VALUES (?1, ?2, ?3)",
                (slot, position as i64, value),
            )
            .unwrap();
        }
    }
    let mut rows = Vec::new();
    for piece in split(statements()[call.name]) {
        let mut statement = db.prepare(&piece).unwrap();
        for index in 1..=statement.parameter_count() {
            let name = statement.parameter_name(index).unwrap().to_owned();
            let value = call
                .values
                .iter()
                .find(|(param, _)| *param == &name[1..])
                .map(|(_, value)| value.clone())
                .unwrap_or(Value::Null);
            statement.raw_bind_parameter(index, value).unwrap();
        }
        let names: Vec<String> = statement.column_names().map(str::to_owned).collect();
        let mut read = statement.raw_query();
        while let Some(row) = read.next().unwrap() {
            let cells = names
                .iter()
                .enumerate()
                .map(|(index, name)| (name.clone(), row.get::<_, Value>(index).unwrap()))
                .collect();
            rows.push(cells);
        }
    }
    rows
}

/// Every app read naming a body answers it by its hash (STORAGE §5), never
/// by the `objects` id a pointer column holds since storage-draft-17, and
/// every app write naming one by its hash points at its row: a pointer
/// read raw would reach Java as an integer, a hash compared raw would
/// match nothing, and neither fails to prepare.
#[test]
fn every_pointer_read_answers_a_hash() {
    let db = store(Text::Shape);
    db.execute_batch(&format!(
        "UPDATE items SET conflicted = 1,
           conflict_object = (SELECT id FROM objects WHERE hash = '{c}')
         WHERE collection = 'INBOX' AND link_id = 'm1';
         UPDATE bindings SET conflicted = 1, conflict_revision = 'r2',
           conflict_object = (SELECT id FROM objects WHERE hash = '{d}')
         WHERE collection = 'INBOX' AND handle = '11';",
        c = hash('c'),
        d = hash('d'),
    ))
    .unwrap();

    let item = |name: &'static str, link: &str| {
        call(
            name,
            vec![
                ("collection", text("INBOX")),
                ("link_id", text(link)),
                ("source", text("imap")),
            ],
        )
    };
    let reads = vec![
        item("ITEM_OBJECT", "m2"),
        item("HELD_ITEM", "m1"),
        item("ITEM_OBJECT_FLAGS", "m2"),
        item("ITEM_CONFLICT_OBJECT", "m1"),
        item("BINDING_BASE_OBJECT", "m2"),
        item("BINDING_CONFLICT_OBJECT", "m2"),
        item("BINDING_SHARED_OBJECT", "m1"),
        item("ITEM_BINDING_OBJECTS", "m2"),
        item("ITEM_BINDINGS", "m1"),
        item("CONFLICT_BODIES", "m1"),
        item("PUSH_ROW", "m2"),
        item("OVERRULED_STATE", "m1"),
        call(
            "ANCESTOR_OBJECT",
            vec![
                ("collection", text("INBOX")),
                ("link_id", text("m1")),
                ("object", text(&hash('y'))),
                ("diverging", text(&hash('z'))),
            ],
        ),
        call(
            "LOAD_PLACEMENTS",
            vec![("collection", text("INBOX")), ("source", text("imap"))],
        ),
        Call {
            name: "LOAD_PLACEMENTS_BY_LINK",
            values: vec![("collection", text("INBOX")), ("source", text("imap"))],
            lists: vec![("links", vec![text("m1"), text("m2")])],
        },
        call("QUEUED_PIN", vec![("id", Value::Integer(1))]),
        call(
            "HELD_OBJECT",
            vec![("link_id", text("m1")), ("account", Value::Null)],
        ),
        call(
            "HELD_MESSAGE_OBJECT",
            vec![("link_id", text("m1")), ("account", text("a"))],
        ),
        call(
            "LIST_LIVE_ENTRIES",
            vec![("kind", text("message/rfc822")), ("source", text("imap"))],
        ),
        call(
            "CARD_PENDING",
            vec![("collection", text("book")), ("source", text("other"))],
        ),
        call(
            "BINDING_CONFLICTS",
            vec![("collection", text("INBOX")), ("source", text("imap"))],
        ),
        call(
            "ITEM_CONFLICTS",
            vec![("collection", text("INBOX")), ("source", text("imap"))],
        ),
        call(
            "LIST_CARDS",
            vec![("collection", text("book")), ("source", text("server"))],
        ),
        Call {
            name: "MAIL_PINS_SORTED_BEFORE",
            values: vec![("before", Value::Null), ("limit", Value::Integer(-1))],
            lists: vec![("collections", vec![text("INBOX")])],
        },
        call(
            "MAIL_PINS_DATED_BEFORE",
            vec![
                ("collection", text("INBOX")),
                ("before", text("2027-01-01T00:00:00Z")),
                ("limit", Value::Integer(-1)),
            ],
        ),
    ];
    let is_hash = |value: &Value| match value {
        Value::Text(text) => {
            text.len() == 52 && text.bytes().all(|b| matches!(b, b'a'..=b'z' | b'2'..=b'7'))
        }
        _ => false,
    };
    for read in &reads {
        let rows = read_named(&db, read);
        let mut named = 0;
        for (column, value) in rows.iter().flatten() {
            if !(column.contains("object") || column.contains("hash")) || *value == Value::Null {
                continue;
            }
            assert!(is_hash(value), "{}: {column} answers {value:?}", read.name);
            named += 1;
        }
        assert!(named > 0, "{} names no body: {rows:?}", read.name);
    }

    let pointer = |sql: &str| -> Value { db.query_row(sql, [], |row| row.get(0)).unwrap() };
    for (name, values) in [
        (
            "WRITE_BINDING",
            vec![
                ("collection", text("INBOX")),
                ("link_id", text("m2")),
                ("source", text("jmap")),
                ("handle", text("j1")),
                ("base_flags_known", Value::Integer(0)),
                ("base_object", text(&hash('b'))),
                ("base_present", Value::Integer(1)),
                ("conflicted", Value::Integer(0)),
                ("shared_object", text(&hash('b'))),
            ],
        ),
        (
            "SET_ITEM_CONFLICT",
            vec![
                ("collection", text("INBOX")),
                ("link_id", text("m2")),
                ("conflicted", Value::Integer(1)),
                ("conflict_object", text(&hash('d'))),
            ],
        ),
    ] {
        read_named(&db, &call(name, values));
    }
    let b = pointer(&format!(
        "SELECT id FROM objects WHERE hash = '{}'",
        hash('b')
    ));
    let d = pointer(&format!(
        "SELECT id FROM objects WHERE hash = '{}'",
        hash('d')
    ));
    assert_eq!(
        pointer("SELECT base_object FROM bindings WHERE source = 'jmap'"),
        b
    );
    assert_eq!(
        pointer("SELECT shared_object FROM bindings WHERE source = 'jmap'"),
        b
    );
    assert_eq!(
        pointer("SELECT conflict_object FROM items WHERE collection = 'INBOX' AND link_id = 'm2'"),
        d
    );

    let bound = read_named(
        &db,
        &call(
            "BOUND_ELSEWHERE",
            vec![
                ("collection", text("Archive")),
                ("link_id", text("m1")),
                ("source", text("imap")),
                ("object", text(&hash('a'))),
            ],
        ),
    );
    assert_eq!(
        bound.len(),
        1,
        "a holder found by its body's hash: {bound:?}"
    );
}
