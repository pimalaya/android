package org.pimalaya.pimdir;

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
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * The pimdir store on the bundled SQLite: schema, invariants, blob directory.
 *
 * <p>Not that SQLite works, but that the spec's schema behaves through the Java
 * binding as through rusqlite: cascades, unique indexes biting, one store
 * holding several accounts and media types.
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
                "INSERT INTO items(collection, link_id, seq, level, flags_known) VALUES(?, ?, ?, 2, 1)",
                new Object[] {collection, linkId, seq});
        db.execSQL(
                "INSERT INTO bindings(collection, link_id, source, handle) VALUES(?, ?, 'server', ?)",
                new Object[] {collection, linkId, linkId + ".vcf"});
    }

    /**
     * The store's opener runs on a SQLite new enough for the schema.
     *
     * <p>Android below 14 ships 3.18 to 3.32, which parses none of the features
     * below. On the host JVM this is the bundled binding over the same
     * amalgamation (flake.nix).
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

        // NOTE: STRICT (3.37) enforced, not merely parsed: the canonical
        // temp.param is one (create_param).
        db.execSQL("CREATE TABLE probe(id INTEGER PRIMARY KEY, n INTEGER NOT NULL) STRICT");
        try {
            db.execSQL("INSERT INTO probe(n) VALUES('not a number')");
            throw new AssertionError("a STRICT table took text into an INTEGER column");
        } catch (SQLiteConstraintException expected) {
            // NOTE: SQLITE_CONSTRAINT_DATATYPE, the STRICT refusal.
        }

        // NOTE: RETURNING (3.35).
        try (Cursor cursor = db.rawQuery("INSERT INTO probe(n) VALUES(1) RETURNING id", null)) {
            assertTrue(cursor.moveToFirst());
            assertEquals(1, cursor.getLong(0));
        }

        // NOTE: UPDATE ... FROM (3.33) over json_each (JSON1), which the
        // statements the app still writes by hand use until the 3.18 shape
        // (cairn/changes/the-shape-follows-storage-draft-08, step 2b).
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
            // NOTE: the recorded algorithm has to be the one that actually
            // named the blobs: a store claiming blake3 while its objects are
            // SHA-256 would be unreadable to every other pimdir consumer.
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
            // NOTE: items_by_seq is UNIQUE(collection, seq): a client resolves
            // an item by (collection, seq), so a clash would make that
            // ambiguous.
        }
    }

    @Test
    public void oneIdentityInTwoAccountsIsTwoPlacementsSharingASeq() {
        seed("work/Contacts", "work", "text/vcard", "uid-alice", 7);
        seed("home/Contacts", "home", "text/vcard", "uid-alice", 7);

        // NOTE: the account groups and partitions nothing (pimdir SPEC.md
        // §9.2): the same identity in two accounts is two rows sharing one
        // public id, and whether that means one person is the interface's call,
        // not the store's.
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
    public void blobsAreShardedOnceImmutableAndAtomic() throws IOException {
        PimdirBlobs blobs = new PimdirBlobs(store.blobs());
        byte[] card = "BEGIN:VCARD\r\nEND:VCARD\r\n".getBytes(StandardCharsets.UTF_8);
        String hash = PimdirHash.of(card);

        assertFalse(blobs.has(hash));
        blobs.put(hash, card);

        assertTrue(blobs.has(hash));
        assertEquals("BEGIN:VCARD\r\nEND:VCARD\r\n", blobs.getText(hash));

        // NOTE: one shard level, objects/<hash[0:2]>/<hash> (STORAGE §5).
        File path = blobs.pathOf(hash);
        assertEquals(hash, path.getName());
        assertEquals(hash.substring(0, 2), path.getParentFile().getName());
        assertEquals(store.blobs(), path.getParentFile().getParentFile());

        // NOTE: re-filing the same hash is a no-op: the name is the content.
        blobs.put(hash, card);
        assertEquals("BEGIN:VCARD\r\nEND:VCARD\r\n", blobs.getText(hash));

        assertEquals(0, path.getParentFile().listFiles(
                (dir, name) -> name.startsWith(".")).length);

        assertTrue(blobs.remove(hash));
        assertNull(blobs.getText(hash));
    }

    /**
     * A write never replaces a file under a name (STORAGE §5), and a body
     * failing its name is never served: it moves into quarantine/ (§7).
     */
    @Test
    public void aBodyFailingItsNameIsQuarantinedNeverReplacedNorServed() throws IOException {
        PimdirBlobs blobs = new PimdirBlobs(store.blobs());
        byte[] body = {1, 2, 3};
        String hash = PimdirHash.of(body);
        File path = blobs.pathOf(hash);
        assertTrue(path.getParentFile().mkdirs());
        java.nio.file.Files.write(path.toPath(), new byte[] {9, 9});

        blobs.put(hash, body);
        assertArrayEquals(
                "a name held keeps what holds it", new byte[] {9, 9},
                java.nio.file.Files.readAllBytes(path.toPath()));

        assertFalse("verified before it is served", blobs.verified(hash));
        assertFalse(blobs.has(hash));
        assertEquals(1, blobs.quarantined().size());
        assertTrue(blobs.quarantined().get(0).startsWith(hash + "."));

        blobs.put(hash, body);
        assertArrayEquals(body, blobs.get(hash));
    }

    @Test
    public void aBodyReachingTwoAccountsIsStoredOnce() throws IOException {
        PimdirBlobs blobs = new PimdirBlobs(store.blobs());
        byte[] body = {1, 2, 3};
        String hash = PimdirHash.of(body);
        blobs.put(hash, body);

        seed("work/Contacts", "work", "text/vcard", "uid-alice", 1);
        seed("home/Contacts", "home", "text/vcard", "uid-alice", 1);
        db.execSQL("INSERT INTO objects(hash, size, refcount) VALUES(?, 3, 2)",
                new Object[] {hash});
        db.execSQL("UPDATE items SET object_hash = ?", new Object[] {hash});

        try (Cursor cursor =
                db.rawQuery("SELECT count(*), count(DISTINCT object_hash) FROM items", null)) {
            assertTrue(cursor.moveToFirst());
            assertEquals(2, cursor.getInt(0));
            assertEquals(1, cursor.getInt(1));
        }
        assertArrayEquals(body, blobs.get(hash));
    }

    /**
     * A store this app creates names its draft and its identity (STORAGE
     * §4.2, §4.5), and opens again.
     */
    @Test
    public void aFreshStoreNamesItsDraftAndItsIdentity() {
        try (Cursor cursor =
                db.rawQuery(
                        "SELECT format_version, store_id, incarnation, derivation, sync_version,"
                                + " read_version, produce_version FROM store_meta",
                        null)) {
            assertTrue(cursor.moveToFirst());
            assertEquals("storage-draft-08", cursor.getString(0));
            assertTrue(cursor.getString(1), cursor.getString(1).matches("[0-9a-f]{32}"));
            assertEquals(1, cursor.getInt(2));
            assertEquals(PimdirSql.identity().optLong("derivation"), cursor.getLong(3));
            assertTrue("written by the first sync write", cursor.isNull(4));
            assertEquals(1, cursor.getInt(5));
            assertEquals(1, cursor.getInt(6));
        }
        store.close();
        assertNull(PimdirDb.refusal(RuntimeEnvironment.getApplication()));
    }

    /**
     * A store an earlier draft wrote (io-pimdir 8907303's store_meta, no
     * format_version, sha256-128) is refused, never reconciled: its file is
     * left byte for byte as it was (STORAGE §6).
     */
    @Test
    public void aStoreOfAnEarlierDraftIsRefusedAndLeftAsItWas() throws Exception {
        store.close();
        File file = new File(store.blobs().getParentFile(), "pimdir.db");
        for (String suffix : new String[] {"", "-wal", "-shm"}) {
            new File(file.getPath() + suffix).delete();
        }
        SQLiteDatabase old = SQLiteDatabase.openOrCreateDatabase(file, null);
        old.execSQL(
                "CREATE TABLE store_meta (id INTEGER PRIMARY KEY CHECK (id = 1),"
                        + " format TEXT NOT NULL DEFAULT 'pimdir', version INTEGER NOT NULL,"
                        + " hash_algo TEXT NOT NULL, created_at TEXT NOT NULL) STRICT");
        old.execSQL(
                "INSERT INTO store_meta(id, version, hash_algo, created_at)"
                        + " VALUES(1, 1, 'sha256-128', '2026-10-01T00:00:00Z')");
        old.execSQL("CREATE TABLE items (collection TEXT, link_id TEXT, flags TEXT)");
        old.setVersion(1);
        old.close();
        byte[] before = java.nio.file.Files.readAllBytes(file.toPath());

        org.json.JSONObject refusal = PimdirDb.refusal(RuntimeEnvironment.getApplication());

        assertEquals(refusal == null ? null : refusal.toString(), "store-version",
                refusal == null ? null : refusal.optString("refused"));
        assertArrayEquals("nothing written", before, java.nio.file.Files.readAllBytes(file.toPath()));
        assertFalse("no journal opened", new File(file.getPath() + "-wal").exists());
    }

    /** A store of this draft whose schema text is not the canonical one. */
    @Test
    public void aDamagedStoreIsRefused() {
        db.execSQL("CREATE TABLE extra (x)");
        store.close();

        org.json.JSONObject refusal = PimdirDb.refusal(RuntimeEnvironment.getApplication());

        assertEquals("store-corrupt", refusal == null ? null : refusal.optString("refused"));
    }
}
