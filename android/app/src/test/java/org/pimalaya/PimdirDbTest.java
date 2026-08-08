package org.pimalaya;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.database.Cursor;
import android.database.sqlite.SQLiteConstraintException;
import android.database.sqlite.SQLiteDatabase;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

import java.io.File;
import java.io.IOException;

/**
 * The pimdir store on Android's own SQLite: the schema, its invariants, and the
 * blob directory beside it.
 *
 * <p>What is worth testing here is not that SQLite works, but that the
 * specification's schema behaves the same way through the platform driver as it
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
}
