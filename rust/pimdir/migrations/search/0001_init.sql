-- Fork of MIGRATION_0001 (sha256 d102db0944466149) for SQLite 3.18: the
-- search index schema, version 1, in the app's own shape, `pimalaya-android`
-- (SEARCH §2). Section references are to SEARCH.md.
--
-- Against the canonical text: no STRICT (3.37), each column's type kept by a
-- CHECK; FTS4 in place of FTS5, whose `contentless_delete` needs 3.43 and
-- which the platform's SQLite never compiles. Both full-text tables are
-- ordinary FTS4 tables: they store a copy of the text they index and delete
-- by docid, FTS4 having no delete on a contentless table. The text arrives
-- folded (§5), so the tokenizer folds no diacritic of its own
-- (`remove_diacritics=0`), and `unicode61` splits words as FTS5's does.
--
-- Applied to index.db, beside pimdir.db, in one transaction, the index's own
-- `PRAGMA user_version` set to 1 on success. Derived state: an older version
-- of this shape, or a mismatch of the tokenizer, the schema text or the store
-- (§2), is a rebuild, never a migration. index.db runs in rollback-journal
-- mode: a query attaches pimdir.db, which costs its handle write-ahead
-- logging on the platform.

-- Index-level metadata: exactly one row. `shape` is `pimalaya-android`.
CREATE TABLE index_meta (
    id            INTEGER PRIMARY KEY CHECK (id = 1),
    format        TEXT    NOT NULL DEFAULT 'pimdir-index' CHECK (typeof(format) = 'text'),
    format_version TEXT   NOT NULL CHECK (typeof(format_version) = 'text'),
    shape         TEXT    NOT NULL CHECK (typeof(shape) = 'text'),
    version       INTEGER NOT NULL CHECK (typeof(version) = 'integer'),
    -- The FTS4 tokenize argument in force (§5).
    tokenizer     TEXT    NOT NULL CHECK (typeof(tokenizer) = 'text'),
    store_id          TEXT    NOT NULL CHECK (typeof(store_id) = 'text'),
    store_incarnation INTEGER NOT NULL CHECK (typeof(store_incarnation) = 'integer'),
    store_change  INTEGER NOT NULL DEFAULT 0 CHECK (typeof(store_change) = 'integer'),
    store_purges  INTEGER NOT NULL DEFAULT 0 CHECK (typeof(store_purges) = 'integer'),
    horizon_start TEXT    NOT NULL DEFAULT '' CHECK (typeof(horizon_start) = 'text'),
    horizon_end   TEXT    NOT NULL DEFAULT '' CHECK (typeof(horizon_end) = 'text'),
    tzdb_version  TEXT    NOT NULL DEFAULT '' CHECK (typeof(tzdb_version) = 'text'),
    floating_zone TEXT    NOT NULL DEFAULT 'UTC' CHECK (typeof(floating_zone) = 'text'),
    -- The revision of §6 the bodies were extracted and the occurrences
    -- expanded under, and the indexer's own (§2): an index recording
    -- another of either is rebuilt.
    extraction    INTEGER NOT NULL CHECK (typeof(extraction) = 'integer'),
    extractor     TEXT    NOT NULL CHECK (typeof(extractor) = 'text')
);

-- One row per indexed body, keyed on the store's object hash. `id` is the
-- docid of its object_text row.
CREATE TABLE object (
    id     INTEGER PRIMARY KEY,
    hash   TEXT    NOT NULL UNIQUE CHECK (length(hash) = 52 AND hash NOT GLOB '*[^a-z2-7]*'),
    status TEXT    NOT NULL CHECK (typeof(status) = 'text'),
    attachment INTEGER NOT NULL CHECK (attachment IN (0, 1)),
    tel    INTEGER NOT NULL CHECK (tel IN (0, 1)),
    adr    INTEGER NOT NULL CHECK (adr IN (0, 1)),
    bday   INTEGER NOT NULL CHECK (bday IN (0, 1)),
    photo  INTEGER NOT NULL CHECK (photo IN (0, 1))
);

-- The bodies the extraction did not read whole (§6), encrypted or
-- unparseable, so a query's coverage counts them by a seek (§8.2).
CREATE INDEX object_not_ok ON object(status, hash) WHERE status <> 'ok';

CREATE TABLE object_category (
    object INTEGER NOT NULL REFERENCES object(id) ON DELETE CASCADE,
    value  TEXT    NOT NULL CHECK (typeof(value) = 'text'),
    PRIMARY KEY (object, value)
);

CREATE INDEX object_category_by_value ON object_category(value);

-- The text of a body, by field (§6), docid = object.id. An ordinary FTS4
-- table: it keeps the text it indexes, so a delete by docid removes the
-- tokens a body gave.
CREATE VIRTUAL TABLE object_text USING fts4(
    title, people, body, attachment, place, org, note,
    "from", "to", cc, bcc, email, organizer, attendee,
    tokenize=unicode61 "remove_diacritics=0", prefix="2,3"
);

CREATE TABLE chunk (
    object INTEGER NOT NULL REFERENCES object(id) ON DELETE CASCADE,
    ord    INTEGER NOT NULL CHECK (typeof(ord) = 'integer'),
    field  TEXT    NOT NULL CHECK (typeof(field) = 'text'),
    start  INTEGER NOT NULL CHECK (typeof(start) = 'integer'),
    end    INTEGER NOT NULL CHECK (typeof(end) = 'integer'),
    PRIMARY KEY (object, ord)
);

-- `id` is the docid of its summary_text row.
CREATE TABLE placement (
    id         INTEGER PRIMARY KEY,
    collection TEXT    NOT NULL CHECK (typeof(collection) = 'text'),
    seq        INTEGER NOT NULL CHECK (typeof(seq) = 'integer'),
    link_id    TEXT    NOT NULL CHECK (typeof(link_id) = 'text'),
    hash       TEXT    CHECK (length(hash) = 52 AND hash NOT GLOB '*[^a-z2-7]*'),
    cut        TEXT    CHECK (typeof(cut) IN ('text', 'null')),
    UNIQUE (collection, seq)
);

CREATE INDEX placement_by_hash ON placement(hash);
CREATE INDEX placement_by_link ON placement(link_id);
CREATE INDEX placement_cut ON placement(collection, cut) WHERE cut IS NOT NULL;

CREATE TABLE missing (
    collection TEXT    NOT NULL CHECK (typeof(collection) = 'text'),
    seq        INTEGER NOT NULL CHECK (typeof(seq) = 'integer'),
    PRIMARY KEY (collection, seq),
    FOREIGN KEY (collection, seq) REFERENCES placement(collection, seq) ON DELETE CASCADE
);

-- The summary of a bodiless placement (§6), docid = placement.id, an
-- ordinary FTS4 table as object_text is.
CREATE VIRTUAL TABLE summary_text USING fts4(
    title, people, "from", "to", cc, bcc, email, organizer, attendee,
    tokenize=unicode61 "remove_diacritics=0", prefix="2,3"
);

CREATE TABLE flag (
    flag       TEXT    NOT NULL CHECK (typeof(flag) = 'text'),
    collection TEXT    NOT NULL CHECK (typeof(collection) = 'text'),
    seq        INTEGER NOT NULL CHECK (typeof(seq) = 'integer'),
    PRIMARY KEY (flag, collection, seq),
    FOREIGN KEY (collection, seq) REFERENCES placement(collection, seq) ON DELETE CASCADE
);

CREATE INDEX flag_by_placement ON flag(collection, seq);

CREATE TABLE occurrence (
    collection TEXT    NOT NULL CHECK (typeof(collection) = 'text'),
    seq        INTEGER NOT NULL CHECK (typeof(seq) = 'integer'),
    start      TEXT    NOT NULL CHECK (typeof(start) = 'text'),
    end        TEXT    NOT NULL CHECK (typeof(end) = 'text'),
    FOREIGN KEY (collection, seq) REFERENCES placement(collection, seq) ON DELETE CASCADE
);

CREATE INDEX occurrence_by_start ON occurrence(start, end);
CREATE INDEX occurrence_by_item ON occurrence(collection, seq);

CREATE TABLE thread (
    id    TEXT    PRIMARY KEY CHECK (typeof(id) = 'text'),
    first TEXT    NOT NULL CHECK (typeof(first) = 'text'),
    last  TEXT    NOT NULL CHECK (typeof(last) = 'text'),
    count INTEGER NOT NULL CHECK (typeof(count) = 'integer')
);

CREATE TABLE message (
    link_id TEXT PRIMARY KEY CHECK (typeof(link_id) = 'text'),
    thread  TEXT NOT NULL REFERENCES thread(id) ON DELETE CASCADE,
    parent  TEXT CHECK (typeof(parent) IN ('text', 'null'))
);

CREATE INDEX message_by_thread ON message(thread);
CREATE INDEX message_by_parent ON message(parent);
