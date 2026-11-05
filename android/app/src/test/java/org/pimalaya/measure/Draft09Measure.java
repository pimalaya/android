package org.pimalaya.measure;

import static org.junit.Assert.assertEquals;
import static org.junit.Assume.assumeTrue;

import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteDoneException;
import android.database.sqlite.SQLiteStatement;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.pimalaya.client.PimdirSql;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * pimdir's storage-draft-09 candidate on the app's write path, timed on the
 * host (pimdir's cairn/changes/performance-audit-2026-11-05, phase 0).
 *
 * <p>The statements a fresh listed message runs through PimdirStorage on
 * storage-draft-08, in the app's shape for SQLite 3.18, run by hand on the
 * bench's draft-08 schema; then the same message adapted to the candidate
 * (bench/draft09-id.sql): its system flags in the item's and the binding's
 * insert, its children keyed by the item's rowid, no flag row without a
 * keyword. Both through the platform's classes over SQLite 3.18.2, every
 * statement compiled once per page and rebound, so the two differ by the
 * schema and the statements alone. Run by hand, with {@code
 * PIMALAYA_MEASURE} set and {@code PIMALAYA_DRAFT09} naming the directory
 * bench/variants.pl wrote (rows.sql, id.sql).
 */
@RunWith(RobolectricTestRunner.class)
public class Draft09Measure {
    private static final int PAGE = 500;

    /** The pages timed after the first, which compiles the statements. */
    private static final int PAGES = 5;

    private static final String INBOX = "acct/INBOX";

    private static final Instant TOP = Instant.parse("2026-10-01T12:00:00Z");

    @Test
    public void draft08AsTheAppWritesIt() throws Exception {
        run("rows", false);
    }

    @Test
    public void draft09Candidate() throws Exception {
        run("id", true);
    }

    private static void run(String family, boolean candidate) throws Exception {
        String dir = System.getenv("PIMALAYA_DRAFT09");
        assumeTrue(System.getenv("PIMALAYA_MEASURE") != null && dir != null);
        File file = new File(RuntimeEnvironment.getApplication().getCacheDir(), family + ".db");
        file.delete();
        SQLiteDatabase db = SQLiteDatabase.openOrCreateDatabase(file, null);
        db.setForeignKeyConstraintsEnabled(true);
        db.setMaxSqlCacheSize(SQLiteDatabase.MAX_SQL_CACHE_SIZE);
        String schema =
                new String(
                        Files.readAllBytes(new File(dir, family + ".sql").toPath()),
                        StandardCharsets.UTF_8);
        db.beginTransaction();
        try {
            for (String statement : PimdirSql.split(schema)) {
                if (!statement.isBlank()) {
                    db.execSQL(statement);
                }
            }
            db.execSQL(
                    "INSERT INTO store_meta(id, format, format_version, version, read_version,"
                            + " produce_version, store_id, incarnation, hash_algo, created_at,"
                            + " derivation) VALUES(1, 'pimdir', 'storage-draft-08', 1, 1, 1,"
                            + " lower(hex(randomblob(16))), 1, 'blake3',"
                            + " strftime('%Y-%m-%dT%H:%M:%fZ', 'now'), 3)");
            db.execSQL(
                    "INSERT INTO collections(id, account, kind, name)"
                            + " VALUES('acct/INBOX', 'acct', 'message/rfc822', 'INBOX')");
            db.execSQL(
                    "INSERT INTO collections(id, account, kind, name)"
                            + " VALUES('acct/Contacts', 'acct', 'text/vcard', 'Contacts')");
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
        db.enableWriteAheadLogging();
        db.compileStatement("PRAGMA synchronous = FULL").execute();
        // NOTE: after the journal mode, which reopens the connection: the
        // table is per connection, as the app's create_param is.
        db.execSQL(
                "CREATE TEMP TABLE IF NOT EXISTS param (slot TEXT NOT NULL,"
                        + " position INTEGER NOT NULL, value, PRIMARY KEY (slot, position))");

        StringBuilder pages = new StringBuilder();
        Writer last = null;
        for (int round = 0; round <= PAGES; round++) {
            Writer writer = candidate ? new Candidate(db) : new Draft08(db);
            long began = System.nanoTime();
            db.beginTransaction();
            try {
                for (int index = 0; index < PAGE; index++) {
                    int at = round * PAGE + index;
                    writer.land(at, TOP.minusSeconds(60L * at).toString());
                }
                db.setTransactionSuccessful();
            } finally {
                db.endTransaction();
            }
            long spent = System.nanoTime() - began;
            writer.close();
            pages.append(round == 0 ? " first " : " ").append(spent / 1_000_000).append(" ms");
            last = writer;
        }
        assertEquals((PAGES + 1) * PAGE, scalar(db, "SELECT count(*) FROM items"));
        assertEquals((PAGES + 1) * PAGE, scalar(db, "SELECT count(*) FROM bindings"));
        assertEquals((PAGES + 1) * PAGE, scalar(db, "SELECT count(*) FROM mail_summary"));
        assertEquals(2L * (PAGES + 1) * PAGE, scalar(db, "SELECT count(*) FROM item_address"));
        long seen =
                candidate
                        ? scalar(db, "SELECT count(*) FROM items WHERE marks & 1 = 1")
                        : scalar(db, "SELECT count(*) FROM placement_flag WHERE flag = '\\Seen'");
        assertEquals("every message read", (PAGES + 1) * PAGE, seen);
        db.close();

        long runs = 0;
        long inside = 0;
        for (long[] entry : last.spent.values()) {
            runs += entry[1];
            inside += entry[0];
        }
        System.out.println(
                "draft09 " + family + ": " + PAGE + " messages a page, pages" + pages
                        + "; last page " + inside / 1_000_000 + " ms in statements, "
                        + String.format("%.1f", runs / (double) PAGE) + " runs a message");
        for (Map.Entry<String, long[]> entry : last.spent.entrySet()) {
            System.out.printf(
                    "draft09 %s   %-24s %5d x %7.2f ms%n",
                    family, entry.getKey(), entry.getValue()[1], entry.getValue()[0] / 1e6);
        }
    }

    private static long scalar(SQLiteDatabase db, String sql) {
        return db.compileStatement(sql).simpleQueryForLong();
    }

    /** A page's statements, compiled once and rebound, each run timed. */
    private abstract static class Writer {
        final SQLiteDatabase db;
        final Map<String, SQLiteStatement> compiled = new LinkedHashMap<>();
        final Map<String, long[]> spent = new LinkedHashMap<>();

        Writer(SQLiteDatabase db) {
            this.db = db;
        }

        /** Lands one fresh listed message, read and flagged, from and to. */
        abstract void land(int at, String date);

        void prepare(String name, String sql) {
            compiled.put(name, db.compileStatement(sql));
        }

        private SQLiteStatement bound(String name, Object... args) {
            SQLiteStatement statement = compiled.get(name);
            statement.clearBindings();
            for (int index = 0; index < args.length; index++) {
                Object value = args[index];
                if (value == null) {
                    statement.bindNull(index + 1);
                } else if (value instanceof Number) {
                    statement.bindLong(index + 1, ((Number) value).longValue());
                } else {
                    statement.bindString(index + 1, value.toString());
                }
            }
            return statement;
        }

        private void count(String name, long began) {
            long[] entry = spent.computeIfAbsent(name, key -> new long[2]);
            entry[0] += System.nanoTime() - began;
            entry[1]++;
        }

        /** A read of one value, null for no row. */
        String read(String name, Object... args) {
            long began = System.nanoTime();
            try {
                return bound(name, args).simpleQueryForString();
            } catch (SQLiteDoneException none) {
                return null;
            } finally {
                count(name, began);
            }
        }

        long insert(String name, Object... args) {
            long began = System.nanoTime();
            try {
                return bound(name, args).executeInsert();
            } finally {
                count(name, began);
            }
        }

        void write(String name, Object... args) {
            long began = System.nanoTime();
            try {
                bound(name, args).executeUpdateDelete();
            } finally {
                count(name, began);
            }
        }

        void close() {
            for (SQLiteStatement statement : compiled.values()) {
                statement.close();
            }
        }
    }

    /** What PimdirStorage runs for a fresh mail upsert today, 28 runs. */
    private static final class Draft08 extends Writer {
        Draft08(SQLiteDatabase db) {
            super(db);
            prepare("LINK_FOR_HANDLE",
                    "SELECT link_id FROM bindings WHERE collection = ? AND source = ? AND handle = ?");
            prepare("HELD_ITEM",
                    "SELECT ifnull(object_hash, '') FROM items WHERE collection = ? AND link_id = ?");
            prepare("SEQ_FOR_LINK_ANY",
                    "SELECT held.seq FROM items AS held JOIN collections AS there"
                            + " ON there.id = held.collection JOIN collections AS here ON here.id = ?"
                            + " WHERE held.link_id = ? AND there.kind = here.kind LIMIT 1");
            prepare("ITEM_BOUND",
                    "SELECT 1 FROM bindings WHERE collection = ? AND link_id = ? LIMIT 1");
            prepare("LOAD_KIND", "SELECT kind FROM collections WHERE id = ?");
            prepare("BUMP_NEXT_SEQ update",
                    "UPDATE store_meta SET next_seq = next_seq + 1 WHERE id = 1");
            prepare("BUMP_NEXT_SEQ read", "SELECT next_seq - 1 FROM store_meta WHERE id = 1");
            prepare("INSERT_ITEM",
                    "INSERT INTO items(collection, link_id, seq, flags_known, object_hash, sort_key,"
                            + " level, deleted, conflicted, conflict_object)"
                            + " VALUES(?, ?, ?, ?, ?, ?, ?, ?, ?, ?)");
            prepare("SET_FLAGS_KNOWN",
                    "UPDATE items SET flags_known = ? WHERE collection = ? AND link_id = ?");
            prepare("CLEAR_PARAM", "DELETE FROM temp.param");
            prepare("BIND_PARAM",
                    "INSERT INTO temp.param(slot, position, value) VALUES(?, ?, ?)");
            prepare("RETAIN_FLAGS",
                    "DELETE FROM placement_flag WHERE collection = ? AND link_id = ? AND flag NOT IN"
                            + " (SELECT value FROM temp.param WHERE slot = 'flags')");
            prepare("ADD_FLAGS",
                    "INSERT INTO placement_flag(collection, link_id, flag) SELECT DISTINCT ?, ?,"
                            + " p.value FROM temp.param p WHERE p.slot = 'flags' AND NOT EXISTS"
                            + " (SELECT 1 FROM placement_flag f WHERE f.collection = ?"
                            + " AND f.link_id = ? AND f.flag = p.value)");
            prepare("WRITE_BINDING update",
                    "UPDATE bindings SET handle = ?, base_flags_known = ?, base_object = ?,"
                            + " base_revision = ?, base_present = ?, conflicted = ?,"
                            + " conflict_revision = ?, conflict_object = ?, shared_object = ?"
                            + " WHERE collection = ? AND link_id = ? AND source = ?");
            prepare("WRITE_BINDING insert",
                    "INSERT INTO bindings(collection, link_id, source, handle, base_flags_known,"
                            + " base_object, base_revision, base_present, conflicted,"
                            + " conflict_revision, conflict_object, shared_object)"
                            + " SELECT ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ? WHERE NOT EXISTS"
                            + " (SELECT 1 FROM bindings WHERE collection = ? AND link_id = ?"
                            + " AND source = ?)");
            prepare("SET_BASE_FLAGS_KNOWN",
                    "UPDATE bindings SET base_flags_known = ? WHERE collection = ? AND link_id = ?"
                            + " AND source = ?");
            prepare("RETAIN_BASE_FLAGS",
                    "DELETE FROM binding_base_flag WHERE collection = ? AND link_id = ?"
                            + " AND source = ? AND flag NOT IN"
                            + " (SELECT value FROM temp.param WHERE slot = 'flags')");
            prepare("ADD_BASE_FLAGS",
                    "INSERT INTO binding_base_flag(collection, link_id, source, flag)"
                            + " SELECT DISTINCT ?, ?, ?, p.value FROM temp.param p"
                            + " WHERE p.slot = 'flags' AND NOT EXISTS (SELECT 1 FROM"
                            + " binding_base_flag f WHERE f.collection = ? AND f.link_id = ?"
                            + " AND f.source = ? AND f.flag = p.value)");
            prepare("UPSERT_MAIL_SUMMARY update",
                    "UPDATE mail_summary SET message_id = ?, subject = ?, sender = ?,"
                            + " sender_name = ?, date = ?, size = ?, attachment = ?,"
                            + " invitation = coalesce(?, invitation)"
                            + " WHERE collection = ? AND link_id = ?");
            prepare("UPSERT_MAIL_SUMMARY insert",
                    "INSERT INTO mail_summary(collection, link_id, message_id, subject, sender,"
                            + " sender_name, date, size, attachment, invitation)"
                            + " SELECT ?, ?, ?, ?, ?, ?, ?, ?, ?, ? WHERE NOT EXISTS"
                            + " (SELECT 1 FROM mail_summary WHERE collection = ? AND link_id = ?)");
            prepare("INSERT_ADDRESS",
                    "INSERT INTO item_address(collection, link_id, role, position, address, name)"
                            + " VALUES(?, ?, ?, ?, ?, ?)");
            prepare("REPLACE_IN_REPLY_TO",
                    "DELETE FROM mail_in_reply_to WHERE collection = ? AND link_id = ?");
            prepare("LINK_SENDERS_OF",
                    "INSERT INTO item_reference(from_kind, from_link_id, to_kind, to_link_id,"
                            + " role, origin, created_at) SELECT DISTINCT 'message/rfc822',"
                            + " mi.link_id, 'text/vcard', ci.link_id, 'sender', 'auto',"
                            + " strftime('%Y-%m-%dT%H:%M:%fZ', 'now') FROM items mi"
                            + " JOIN collections mc ON mc.id = mi.collection"
                            + " AND mc.kind = 'message/rfc822' JOIN item_address m"
                            + " ON m.collection = mi.collection AND m.link_id = mi.link_id"
                            + " AND m.role = 'from' JOIN item_address c ON c.address = m.address"
                            + " AND c.role = 'email' JOIN collections cc ON cc.id = c.collection"
                            + " AND cc.kind = 'text/vcard' JOIN items ci ON ci.collection = c.collection"
                            + " AND ci.link_id = c.link_id AND ci.deleted = 0"
                            + " WHERE mi.link_id BETWEEN coalesce(?, '') AND coalesce(?, x'')"
                            + " AND mi.deleted = 0 AND NOT (mi.link_id GLOB 'alt:*'"
                            + " OR mi.link_id GLOB 'dup:*' OR mi.link_id GLOB 'hash:*')"
                            + " AND NOT (ci.link_id GLOB 'alt:*' OR ci.link_id GLOB 'dup:*'"
                            + " OR ci.link_id GLOB 'hash:*') AND NOT EXISTS (SELECT 1 FROM"
                            + " item_reference r WHERE r.from_kind = 'message/rfc822'"
                            + " AND r.from_link_id = mi.link_id AND r.to_kind = 'text/vcard'"
                            + " AND r.to_link_id = ci.link_id AND r.role = 'sender')");
        }

        @Override
        void land(int at, String date) {
            String link = "m" + at;
            read("LINK_FOR_HANDLE", INBOX, "server", link);
            read("HELD_ITEM", INBOX, link);
            read("SEQ_FOR_LINK_ANY", INBOX, link);
            write("BUMP_NEXT_SEQ update");
            long seq = Long.parseLong(read("BUMP_NEXT_SEQ read"));
            insert("INSERT_ITEM", INBOX, link, seq, 1, null, date, 1, 0, 0, null);
            write("SET_FLAGS_KNOWN", 1, INBOX, link);
            flags();
            write("RETAIN_FLAGS", INBOX, link);
            write("ADD_FLAGS", INBOX, link, INBOX, link);
            write("WRITE_BINDING update",
                    link, 1, null, null, 1, 0, null, null, null, INBOX, link, "server");
            write("WRITE_BINDING insert",
                    INBOX, link, "server", link, 1, null, null, 1, 0, null, null, null,
                    INBOX, link, "server");
            write("SET_BASE_FLAGS_KNOWN", 1, INBOX, link, "server");
            flags();
            write("RETAIN_BASE_FLAGS", INBOX, link, "server");
            write("ADD_BASE_FLAGS", INBOX, link, "server", INBOX, link, "server");
            write("UPSERT_MAIL_SUMMARY update",
                    "<" + link + "@example.org>", "Message " + link, "sender@example.org",
                    "Sender", date, 2048, 0, null, INBOX, link);
            write("UPSERT_MAIL_SUMMARY insert",
                    INBOX, link, "<" + link + "@example.org>", "Message " + link,
                    "sender@example.org", "Sender", date, 2048, 0, null, INBOX, link);
            insert("INSERT_ADDRESS", INBOX, link, "from", 0, "sender@example.org", "Sender");
            insert("INSERT_ADDRESS", INBOX, link, "to", 0, "jane@example.com", null);
            write("REPLACE_IN_REPLY_TO", INBOX, link);
            write("LINK_SENDERS_OF", link, link);
            read("ITEM_BOUND", INBOX, link);
            read("LOAD_KIND", INBOX);
        }

        /** The flags bound once for the retain and the add, as the app shares them. */
        private void flags() {
            write("CLEAR_PARAM");
            insert("BIND_PARAM", "flags", 0, "\\Seen");
            insert("BIND_PARAM", "flags", 1, "\\Flagged");
        }
    }

    /**
     * The same message on the candidate: \Seen and \Flagged as marks 5 in the
     * item's and the binding's insert, no keyword so no flag row, every child
     * bound by the item's rowid.
     */
    private static final class Candidate extends Writer {
        Candidate(SQLiteDatabase db) {
            super(db);
            prepare("LINK_FOR_HANDLE",
                    "SELECT i.link_id FROM bindings b JOIN items i ON i.id = b.item"
                            + " WHERE b.collection = ? AND b.source = ? AND b.handle = ?");
            prepare("HELD_ITEM",
                    "SELECT ifnull(object_hash, '') FROM items WHERE collection = ? AND link_id = ?");
            prepare("SEQ_FOR_LINK_ANY",
                    "SELECT held.seq FROM items AS held JOIN collections AS there"
                            + " ON there.id = held.collection JOIN collections AS here ON here.id = ?"
                            + " WHERE held.link_id = ? AND there.kind = here.kind LIMIT 1");
            prepare("ITEM_BOUND",
                    "SELECT 1 FROM bindings b JOIN items i ON i.id = b.item"
                            + " WHERE i.collection = ? AND i.link_id = ? LIMIT 1");
            prepare("LOAD_KIND", "SELECT kind FROM collections WHERE id = ?");
            prepare("BUMP_NEXT_SEQ update",
                    "UPDATE store_meta SET next_seq = next_seq + 1 WHERE id = 1");
            prepare("BUMP_NEXT_SEQ read", "SELECT next_seq - 1 FROM store_meta WHERE id = 1");
            prepare("INSERT_ITEM",
                    "INSERT INTO items(collection, link_id, seq, flags_known, marks, object_hash,"
                            + " sort_key, level, deleted, conflicted, conflict_object)"
                            + " VALUES(?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)");
            prepare("WRITE_BINDING update",
                    "UPDATE bindings SET handle = ?, base_flags_known = ?, base_marks = ?,"
                            + " base_object = ?, base_revision = ?, base_present = ?,"
                            + " conflicted = ?, conflict_revision = ?, conflict_object = ?,"
                            + " shared_object = ? WHERE item = ? AND source = ?");
            prepare("WRITE_BINDING insert",
                    "INSERT INTO bindings(item, collection, source, handle, base_flags_known,"
                            + " base_marks, base_object, base_revision, base_present, conflicted,"
                            + " conflict_revision, conflict_object, shared_object)"
                            + " SELECT ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ? WHERE NOT EXISTS"
                            + " (SELECT 1 FROM bindings WHERE item = ? AND source = ?)");
            prepare("UPSERT_MAIL_SUMMARY update",
                    "UPDATE mail_summary SET message_id = ?, subject = ?, sender = ?,"
                            + " sender_name = ?, date = ?, size = ?, attachment = ?,"
                            + " invitation = coalesce(?, invitation) WHERE item = ?");
            prepare("UPSERT_MAIL_SUMMARY insert",
                    "INSERT INTO mail_summary(item, message_id, subject, sender, sender_name,"
                            + " date, size, attachment, invitation)"
                            + " SELECT ?, ?, ?, ?, ?, ?, ?, ?, ? WHERE NOT EXISTS"
                            + " (SELECT 1 FROM mail_summary WHERE item = ?)");
            prepare("INSERT_ADDRESS",
                    "INSERT INTO item_address(item, role, position, address, name)"
                            + " VALUES(?, ?, ?, ?, ?)");
            prepare("REPLACE_IN_REPLY_TO", "DELETE FROM mail_in_reply_to WHERE item = ?");
            prepare("LINK_SENDERS_OF",
                    "INSERT INTO item_reference(from_kind, from_link_id, to_kind, to_link_id,"
                            + " role, origin, created_at) SELECT DISTINCT 'message/rfc822',"
                            + " mi.link_id, 'text/vcard', ci.link_id, 'sender', 'auto',"
                            + " strftime('%Y-%m-%dT%H:%M:%fZ', 'now') FROM items mi"
                            + " JOIN collections mc ON mc.id = mi.collection"
                            + " AND mc.kind = 'message/rfc822' JOIN item_address m"
                            + " ON m.item = mi.id AND m.role = 'from' JOIN item_address c"
                            + " ON c.address = m.address AND c.role = 'email' JOIN items ci"
                            + " ON ci.id = c.item AND ci.deleted = 0 JOIN collections cc"
                            + " ON cc.id = ci.collection AND cc.kind = 'text/vcard'"
                            + " WHERE mi.link_id BETWEEN coalesce(?, '') AND coalesce(?, x'')"
                            + " AND mi.deleted = 0 AND NOT (mi.link_id GLOB 'alt:*'"
                            + " OR mi.link_id GLOB 'dup:*' OR mi.link_id GLOB 'hash:*')"
                            + " AND NOT (ci.link_id GLOB 'alt:*' OR ci.link_id GLOB 'dup:*'"
                            + " OR ci.link_id GLOB 'hash:*') AND NOT EXISTS (SELECT 1 FROM"
                            + " item_reference r WHERE r.from_kind = 'message/rfc822'"
                            + " AND r.from_link_id = mi.link_id AND r.to_kind = 'text/vcard'"
                            + " AND r.to_link_id = ci.link_id AND r.role = 'sender')");
        }

        @Override
        void land(int at, String date) {
            String link = "m" + at;
            read("LINK_FOR_HANDLE", INBOX, "server", link);
            read("HELD_ITEM", INBOX, link);
            read("SEQ_FOR_LINK_ANY", INBOX, link);
            write("BUMP_NEXT_SEQ update");
            long seq = Long.parseLong(read("BUMP_NEXT_SEQ read"));
            long item = insert("INSERT_ITEM", INBOX, link, seq, 1, 5, null, date, 1, 0, 0, null);
            write("WRITE_BINDING update",
                    link, 1, 5, null, null, 1, 0, null, null, null, item, "server");
            write("WRITE_BINDING insert",
                    item, INBOX, "server", link, 1, 5, null, null, 1, 0, null, null, null,
                    item, "server");
            write("UPSERT_MAIL_SUMMARY update",
                    "<" + link + "@example.org>", "Message " + link, "sender@example.org",
                    "Sender", date, 2048, 0, null, item);
            write("UPSERT_MAIL_SUMMARY insert",
                    item, "<" + link + "@example.org>", "Message " + link,
                    "sender@example.org", "Sender", date, 2048, 0, null, item);
            insert("INSERT_ADDRESS", item, "from", 0, "sender@example.org", "Sender");
            insert("INSERT_ADDRESS", item, "to", 0, "jane@example.com", null);
            write("REPLACE_IN_REPLY_TO", item);
            write("LINK_SENDERS_OF", link, link);
            read("ITEM_BOUND", INBOX, link);
            read("LOAD_KIND", INBOX);
        }
    }
}
