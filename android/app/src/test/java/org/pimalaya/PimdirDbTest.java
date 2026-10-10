package org.pimalaya;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.database.Cursor;
import android.database.sqlite.SQLiteConstraintException;

import io.requery.android.database.sqlite.SQLiteDatabase;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.pimalaya.client.PimdirSql;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

/**
 * The pimdir store on the bundled SQLite: the schema, its invariants, and the
 * blob directory beside it.
 *
 * <p>What is worth testing here is not that SQLite works, but that the
 * specification's schema behaves the same way through the Java binding as it
 * does through rusqlite: the foreign keys cascade, the unique indexes bite, and
 * one store really does hold several accounts and several media types at once.
 */
@RunWith(RobolectricTestRunner.class)
public class PimdirDbTest {
    private PimdirDb store;
    private SQLiteDatabase db;

    @Before
    public void setUp() {
        store = new PimdirDb(RuntimeEnvironment.getApplication());
        db = store.getWritableDatabase();
    }

    /** A collection, an item in it, and the binding of one source. */
    private void seed(String collection, String account, String kind, String linkId, long seq) {
        db.execSQL(
                "INSERT INTO collections(id, account, kind, name) VALUES(?, ?, ?, ?)",
                new Object[] {collection, account, kind, collection});
        db.execSQL(
                "INSERT INTO items(collection, link_id, seq, level, flags) VALUES(?, ?, ?, 2, '[]')",
                new Object[] {collection, linkId, seq});
        db.execSQL(
                "INSERT INTO bindings(collection, link_id, source, handle) VALUES(?, ?, 'server', ?)",
                new Object[] {collection, linkId, linkId + ".vcf"});
    }

    /**
     * The store's opener runs on a SQLite new enough for the schema, whatever
     * the device: Android ships 3.18 to 3.32 below Android 14, which parses
     * none of the features below.
     *
     * <p>On the host this is the bundled binding over the host build of the
     * same amalgamation (flake.nix), not the AAR's Android library itself.
     */
    @Test
    public void theStoreRunsOnASqliteNewEnoughForTheSchema() {
        try (Cursor cursor = db.rawQuery("SELECT sqlite_version()", null)) {
            assertTrue(cursor.moveToFirst());
            String[] version = cursor.getString(0).split("\\.");
            int major = Integer.parseInt(version[0]);
            int minor = Integer.parseInt(version[1]);
            assertTrue(cursor.getString(0), major > 3 || (major == 3 && minor >= 37));
        }

        // STRICT (3.37) enforced, not merely parsed.
        db.execSQL("CREATE TABLE probe(id INTEGER PRIMARY KEY, n INTEGER NOT NULL) STRICT");
        try {
            db.execSQL("INSERT INTO probe(n) VALUES('not a number')");
            throw new AssertionError("a STRICT table took text into an INTEGER column");
        } catch (SQLiteConstraintException expected) {
            // NOTE: SQLITE_CONSTRAINT_DATATYPE, the STRICT refusal.
        }

        // RETURNING (3.35).
        try (Cursor cursor = db.rawQuery("INSERT INTO probe(n) VALUES(1) RETURNING id", null)) {
            assertTrue(cursor.moveToFirst());
            assertEquals(1, cursor.getLong(0));
        }

        // UPDATE ... FROM (3.33) over json_each (JSON1).
        db.execSQL(
                "UPDATE probe SET n = j.value FROM json_each('[41]') AS j WHERE probe.id = 1");
        try (Cursor cursor = db.rawQuery("SELECT n FROM probe WHERE id = 1", null)) {
            assertTrue(cursor.moveToFirst());
            assertEquals(41, cursor.getLong(0));
        }
    }

    @Test
    public void theStoreIsCreatedWithItsMetadata() {
        try (Cursor cursor = db.rawQuery("SELECT format, version, hash_algo FROM store_meta", null)) {
            assertTrue(cursor.moveToFirst());
            assertEquals("pimdir", cursor.getString(0));
            assertEquals(1, cursor.getInt(1));
            // The recorded algorithm has to be the one that actually named the
            // blobs: a store claiming blake3 while its objects are SHA-256
            // would be unreadable to every other pimdir consumer.
            assertEquals(PimdirHash.ALGORITHM, cursor.getString(2));
        }
    }

    @Test
    public void oneStoreHoldsEveryAccountAndEveryKind() {
        seed("work/INBOX", "work", "message/rfc822", "<n@x>", 1);
        seed("work/Contacts", "work", "text/vcard", "uid-alice", 2);
        seed("home/Calendar", "home", "text/calendar", "ev-1", 3);

        try (Cursor cursor =
                db.rawQuery("SELECT count(DISTINCT kind), count(DISTINCT account) FROM collections",
                        null)) {
            assertTrue(cursor.moveToFirst());
            assertEquals("three media types in one store", 3, cursor.getInt(0));
            assertEquals("two accounts in one store", 2, cursor.getInt(1));
        }
    }

    @Test
    public void aCollectionCascadesToItsItemsAndBindings() {
        seed("work/Contacts", "work", "text/vcard", "uid-alice", 1);

        db.execSQL("DELETE FROM collections WHERE id = 'work/Contacts'");

        try (Cursor cursor = db.rawQuery("SELECT count(*) FROM items", null)) {
            assertTrue(cursor.moveToFirst());
            assertEquals("items cascade with their collection", 0, cursor.getInt(0));
        }
        try (Cursor cursor = db.rawQuery("SELECT count(*) FROM bindings", null)) {
            assertTrue(cursor.moveToFirst());
            assertEquals("bindings cascade with their item", 0, cursor.getInt(0));
        }
    }

    @Test
    public void thePublicIdIsUniquePerCollection() {
        seed("work/Contacts", "work", "text/vcard", "uid-alice", 1);

        try {
            db.execSQL(
                    "INSERT INTO items(collection, link_id, seq, level) "
                            + "VALUES('work/Contacts', 'uid-bob', 1, 2)");
            org.junit.Assert.fail("two items of one collection shared a seq");
        } catch (SQLiteConstraintException expected) {
            // items_by_seq is UNIQUE(collection, seq): a client resolves an item
            // by (collection, seq), so a clash would make that ambiguous.
        }
    }

    @Test
    public void oneIdentityInTwoAccountsIsTwoPlacementsSharingASeq() {
        seed("work/Contacts", "work", "text/vcard", "uid-alice", 7);
        seed("home/Contacts", "home", "text/vcard", "uid-alice", 7);

        // The account groups and partitions nothing (pimdir SPEC.md §9.2): the
        // same identity in two accounts is two rows sharing one public id, and
        // whether that means one person is the interface's call, not the
        // store's.
        try (Cursor cursor =
                db.rawQuery(
                        "SELECT i.collection, c.account, i.seq FROM items i"
                                + " JOIN collections c ON c.id = i.collection"
                                + " WHERE i.link_id = 'uid-alice' ORDER BY c.account",
                        null)) {
            assertTrue(cursor.moveToFirst());
            assertEquals("home/Contacts", cursor.getString(0));
            assertEquals(7, cursor.getInt(2));
            assertTrue(cursor.moveToNext());
            assertEquals("work/Contacts", cursor.getString(0));
            assertEquals("the seq is shared across accounts", 7, cursor.getInt(2));
            assertFalse(cursor.moveToNext());
        }
    }

    @Test
    public void blobsAreShardedImmutableAndAtomic() throws IOException {
        PimdirBlobs blobs = new PimdirBlobs(store.blobs());
        String hash = "abcdef0123456789";

        assertFalse(blobs.has(hash));
        blobs.putText(hash, "BEGIN:VCARD\r\nEND:VCARD\r\n");

        assertTrue(blobs.has(hash));
        assertEquals("BEGIN:VCARD\r\nEND:VCARD\r\n", blobs.getText(hash));

        // Sharded two levels, so no directory grows unbounded.
        File path = blobs.pathOf(hash);
        assertEquals(hash, path.getName());
        assertEquals("cd", path.getParentFile().getName());
        assertEquals("ab", path.getParentFile().getParentFile().getName());

        // Re-filing the same hash is a no-op: the name is the content.
        blobs.putText(hash, "BEGIN:VCARD\r\nEND:VCARD\r\n");
        assertEquals("BEGIN:VCARD\r\nEND:VCARD\r\n", blobs.getText(hash));

        // No temp file survives a committed write.
        assertEquals(0, path.getParentFile().listFiles(
                (dir, name) -> name.startsWith(".")).length);

        assertTrue(blobs.remove(hash));
        assertNull(blobs.getText(hash));
    }

    @Test
    public void aBodyReachingTwoAccountsIsStoredOnce() throws IOException {
        PimdirBlobs blobs = new PimdirBlobs(store.blobs());
        String hash = "beef0000beef0000";
        blobs.put(hash, new byte[] {1, 2, 3});

        seed("work/Contacts", "work", "text/vcard", "uid-alice", 1);
        seed("home/Contacts", "home", "text/vcard", "uid-alice", 1);
        db.execSQL("INSERT INTO objects(hash, size, refcount) VALUES(?, 3, 2)",
                new Object[] {hash});
        db.execSQL("UPDATE items SET object_hash = ?", new Object[] {hash});

        // Two placements, one object row, one file on disk.
        try (Cursor cursor =
                db.rawQuery("SELECT count(*), count(DISTINCT object_hash) FROM items", null)) {
            assertTrue(cursor.moveToFirst());
            assertEquals(2, cursor.getInt(0));
            assertEquals(1, cursor.getInt(1));
        }
        assertArrayEquals(new byte[] {1, 2, 3}, blobs.get(hash));
    }

    @Test
    public void aStoreWrittenByAnEarlierDraftIsReconciledOnOpen() {
        // While the spec is a draft, version 1 is edited in place, so such a
        // store is not detectably out of date and onUpgrade never fires: the
        // drift would surface as a query error instead. A draft folds a column
        // both ways, so both directions are checked.
        db.execSQL("ALTER TABLE bindings DROP COLUMN shared_object");
        db.execSQL("ALTER TABLE bindings ADD COLUMN ambiguous_handles TEXT");
        db.execSQL("DROP INDEX items_retained");
        db.execSQL("CREATE INDEX items_retained ON items(collection, link_id)"
                + " WHERE retained_at IS NOT NULL");
        db.execSQL("DROP TABLE contact_summary");
        // A column an index reads, dropped with the index that reads it: the
        // reconcile has to widen the table before it creates what selects on
        // it, or the store is refused a column the schema declares and it does
        // not hold yet.
        db.execSQL("DROP INDEX collections_by_changed");
        db.execSQL("ALTER TABLE collections DROP COLUMN changed");
        db.execSQL("DROP TRIGGER items_count_purge");
        db.execSQL("CREATE TRIGGER items_count_purge AFTER DELETE ON items"
                + " BEGIN UPDATE store_meta SET purges = purges WHERE id = 1; END");
        store.close();

        store = new PimdirDb(RuntimeEnvironment.getApplication());
        db = store.getWritableDatabase();

        assertTrue("a column the schema declares is added back",
                hasColumn("bindings", "shared_object"));
        assertFalse("a column it no longer declares is dropped",
                hasColumn("bindings", "ambiguous_handles"));
        // An index whose columns moved keeps its name, so CREATE INDEX IF NOT
        // EXISTS leaves the old plan in place and only a drop can repair it.
        assertEquals("seq", indexColumns("items_retained"));
        // A table the draft added is created rather than waited for: a store
        // written before the summary tables were declared holds no summary,
        // and every write into one would fail on a table that is not there.
        assertTrue("a table the schema declares is created",
                hasColumn("contact_summary", "fn"));
        assertTrue("a column an index reads is added before the index is",
                hasColumn("collections", "changed"));
        assertEquals("changed", indexColumns("collections_by_changed"));
        // A trigger's body is not a column, so the shape check is its text.
        assertTrue("a trigger whose body moved is rebuilt",
                sqlOf("items_count_purge").contains("purges + 1"));
    }

    @Test
    public void aStoreWrittenBeforeReferencesGainsThemOnOpen() {
        db.execSQL("DROP TRIGGER items_drop_references");
        db.execSQL("DROP TABLE item_reference");
        store.close();

        store = new PimdirDb(RuntimeEnvironment.getApplication());
        db = store.getWritableDatabase();

        assertTrue(hasColumn("item_reference", "role"));
        assertFalse(sqlOf("item_reference_to").isEmpty());
        assertFalse(sqlOf("items_drop_references").isEmpty());
    }

    @Test
    public void aStoreWrittenBeforeFilesGainsThemOnOpen() {
        db.execSQL("DROP TRIGGER item_reference_collects_files");
        db.execSQL("DROP TABLE file_summary");
        store.close();

        store = new PimdirDb(RuntimeEnvironment.getApplication());
        db = store.getWritableDatabase();

        assertTrue(hasColumn("file_summary", "part"));
        assertFalse(sqlOf("item_reference_collects_files").isEmpty());
    }

    @Test
    public void aRoundOpenedBeforeTheBandColumnReadsAsAWholeScopeRound() {
        // A store written before rounds recorded their kind (pimdir
        // 00535c1), with a round open in it: the column is added under the
        // open round, which then reads as one over its whole scope.
        db.execSQL("ALTER TABLE sources DROP COLUMN round_band");
        db.execSQL("INSERT INTO collections(id, account, kind, name)"
                + " VALUES('acct/INBOX', 'acct', 'message/rfc822', 'INBOX')");
        db.execSQL("INSERT INTO sources(collection, source, round, round_since, round_started_at)"
                + " VALUES('acct/INBOX', 'server', 3, '2026-04-01T00:00:00Z',"
                + " '2026-10-07T08:00:00Z')");
        store.close();

        store = new PimdirDb(RuntimeEnvironment.getApplication());
        db = store.getWritableDatabase();

        assertTrue("the round's kind is added", hasColumn("sources", "round_band"));
        try (Cursor cursor =
                db.rawQuery("SELECT round, round_band FROM sources WHERE collection = 'acct/INBOX'",
                        null)) {
            assertTrue("the open round survives", cursor.moveToFirst());
            assertEquals(3, cursor.getInt(0));
            assertEquals(0, cursor.getInt(1));
        }
    }

    @Test
    public void aMergedPageWalksTheGlobalOrderOnAnOlderStore() {
        // A store written before pimdir 22f1f2c holds no global order: a page
        // over several mailboxes read every item of the set and sorted it. The
        // reconcile adds the index on open, and the canonical page statement,
        // whose collection test is kept off items_by_seq, walks it.
        db.execSQL("DROP INDEX items_by_sort_global");
        seed("acct/INBOX", "acct", "message/rfc822", "<a@x>", 1);
        seed("acct/Archive", "acct", "message/rfc822", "<b@x>", 2);
        store.close();

        store = new PimdirDb(RuntimeEnvironment.getApplication());
        db = store.getWritableDatabase();

        assertEquals("the global order is added on open", "collection",
                indexColumns("items_by_sort_global"));

        Map<String, Object> values = new HashMap<>();
        values.put("collections", "[\"acct/INBOX\",\"acct/Archive\"]");
        values.put("limit", 50);
        PimdirSql.Bound page = PimdirSql.bind("LIST_MAIL_PAGE_FILTERED", values);
        StringBuilder plan = new StringBuilder();
        try (Cursor cursor =
                MailStore.typed(db, "EXPLAIN QUERY PLAN " + page.sql, page.args)) {
            while (cursor.moveToNext()) {
                plan.append(cursor.getString(3)).append('\n');
            }
        }
        assertTrue("the page walks the global order:\n" + plan,
                plan.toString().contains("items_by_sort_global"));
        assertFalse("the page sorts nothing:\n" + plan,
                plan.toString().contains("TEMP B-TREE FOR ORDER BY"));
    }

    /** The statement an object was created with, as sqlite_master keeps it. */
    private String sqlOf(String name) {
        try (Cursor cursor =
                db.rawQuery("SELECT sql FROM sqlite_master WHERE name = ?", new String[] {name})) {
            return cursor.moveToFirst() && !cursor.isNull(0) ? cursor.getString(0) : "";
        }
    }

    private boolean hasColumn(String table, String column) {
        try (Cursor cursor = db.rawQuery("PRAGMA table_info(" + table + ")", null)) {
            while (cursor.moveToNext()) {
                if (column.equals(cursor.getString(1))) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * The last column of an index, which is what the reshape moved.
     *
     * <p>Through a {@code SELECT}: a bare pragma may run on a pooled
     * connection whose cached schema predates the reconcile.
     */
    private String indexColumns(String index) {
        String last = null;
        try (Cursor cursor = db.rawQuery(
                "SELECT name FROM pragma_index_info(?) ORDER BY seqno", new String[] {index})) {
            while (cursor.moveToNext()) {
                last = cursor.getString(0);
            }
        }
        return last;
    }
}
