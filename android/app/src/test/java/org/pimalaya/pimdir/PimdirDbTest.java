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
import java.util.List;
import java.util.Map;

/**
 * The pimdir store on the SQLite the tests run on: schema, invariants, blob
 * directory.
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

    /**
     * The blob store never hard links: Android's SELinux policy denies
     * {@code link} to an app on its own data files, which failed every body
     * write on a device while the host, allowing it, passed (found on a
     * OnePlus 5, Android 15, 2026-11-01). The write renames instead.
     */
    @Test
    public void theOwnerNeverHardLinks() throws IOException {
        java.util.List<String> linking = new java.util.ArrayList<>();
        for (String root : new String[] {"src/main/java", "../client/src/main/java"}) {
            try (java.util.stream.Stream<java.nio.file.Path> files =
                    java.nio.file.Files.walk(java.nio.file.Paths.get(root))) {
                for (java.nio.file.Path file :
                        (Iterable<java.nio.file.Path>)
                                files.filter(p -> p.toString().endsWith(".java"))::iterator) {
                    String source = new String(
                            java.nio.file.Files.readAllBytes(file), StandardCharsets.UTF_8);
                    if (source.contains("createLink(")) {
                        linking.add(file.toString());
                    }
                }
            }
        }
        assertEquals(java.util.List.of(), linking);
    }

    /**
     * The no-replace rename (STORAGE §5): a free name takes the source, a
     * name held keeps what holds it and leaves the source where it was.
     */
    @Test
    public void aRenameNeverReplacesAName() throws IOException {
        File dir = new File(store.blobs(), "ab");
        assertTrue(dir.mkdirs());
        File temp = new File(dir, ".x.tmp");
        File name = new File(dir, "x");
        java.nio.file.Files.write(temp.toPath(), new byte[] {1});

        assertTrue(PimdirSql.renameNoReplace(temp, name));
        assertFalse(temp.exists());
        assertArrayEquals(new byte[] {1}, java.nio.file.Files.readAllBytes(name.toPath()));

        java.nio.file.Files.write(temp.toPath(), new byte[] {2});
        assertFalse("held", PimdirSql.renameNoReplace(temp, name));
        assertArrayEquals(new byte[] {1}, java.nio.file.Files.readAllBytes(name.toPath()));
        assertArrayEquals(new byte[] {2}, java.nio.file.Files.readAllBytes(temp.toPath()));
    }

    /** An adopted file is renamed under its name, the source gone after. */
    @Test
    public void anAdoptedFileTakesItsName() throws IOException {
        PimdirBlobs blobs = new PimdirBlobs(store.blobs());
        byte[] body = "downloaded".getBytes(StandardCharsets.UTF_8);
        String hash = PimdirHash.of(body);
        File source = new File(store.blobs().getParentFile(), "download.part");
        java.nio.file.Files.write(source.toPath(), body);

        blobs.adopt(hash, source);

        assertFalse(source.exists());
        assertArrayEquals(body, blobs.get(hash));
        assertEquals(0, blobs.pathOf(hash).getParentFile().listFiles(
                (dir, name) -> name.startsWith(".")).length);
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
     * Opening a fresh store opens the writer alone: a reader opened while the
     * store is configured, before onCreate made its tables, caches the empty
     * schema, and its first statements fail once each ("no such table:
     * collections" on a first launch) before SQLite reloads it.
     */
    @Test
    public void openingAFreshStoreOpensNoReaderBeforeItsSchema() {
        // NOTE: setUp created this test's store, and nothing read it since.
        assertEquals(
                "the writer alone",
                List.of(db.getPath()),
                io.requery.android.database.sqlite.PoolProbe.connections(db.getPath()));
    }

    /**
     * The erasure and the schema trust STORAGE §11.5 and §18 ask hold on the
     * connection that writes, read inside a transaction, which runs there.
     */
    @Test
    public void theWriterErasesAndTrustsNoSchema() {
        db.beginTransaction();
        try {
            assertEquals(1, pragma("PRAGMA secure_delete"));
            // NOTE: SQLite before 3.31 has no trusted_schema and answers no
            // row; one that has it holds it off.
            try (Cursor cursor = db.rawQuery("PRAGMA trusted_schema", null)) {
                assertTrue(!cursor.moveToFirst() || cursor.getLong(0) == 0);
            }
        } finally {
            db.endTransaction();
        }
    }

    private long pragma(String sql) {
        try (Cursor cursor = db.rawQuery(sql, null)) {
            assertTrue(cursor.moveToFirst());
            return cursor.getLong(0);
        }
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
                        + " hash_algo TEXT NOT NULL, created_at TEXT NOT NULL)");
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

    /**
     * A store the canonical statement text wrote (the app before its own
     * shape, step 2a) is the same store: storage-draft-08 has one on-disk
     * format and records no shape (STORAGE §4.1, §6), so it opens, and the
     * shape's statements read and write its rows.
     */
    @Test
    public void aStoreTheCanonicalTextWroteOpensAsTheSameStore() throws Exception {
        store.close();
        File file = new File(store.blobs().getParentFile(), "pimdir.db");
        for (String suffix : new String[] {"", "-wal", "-shm"}) {
            new File(file.getPath() + suffix).delete();
        }
        // NOTE: what 2a ran: io-pimdir's migrations and init_store_meta, then
        // rows its canonical upserts wrote, the same rows whatever text
        // wrote them.
        SQLiteDatabase canonical = SQLiteDatabase.openOrCreateDatabase(file, null);
        canonical.execSQL("PRAGMA auto_vacuum = INCREMENTAL");
        for (String statement : PimdirSql.schema()) {
            canonical.execSQL(statement);
        }
        PimdirSql.Bound meta =
                PimdirSql.bind(
                        "INIT_STORE_META",
                        PimdirSql.values(
                                "version", PimdirSql.version(),
                                "derivation", PimdirSql.identity().optLong("derivation")));
        canonical.execSQL(meta.sql, meta.args);
        canonical.execSQL(
                "INSERT INTO collections(id, account, kind, name) VALUES('INBOX', 'a',"
                        + " 'message/rfc822', 'INBOX')");
        canonical.execSQL(
                "INSERT INTO items(collection, link_id, seq, level) VALUES('INBOX', 'm1', 1, 1)");
        canonical.execSQL(
                "INSERT INTO mail_summary(collection, link_id, subject, invitation)"
                        + " VALUES('INBOX', 'm1', 'one', 'e1')");
        canonical.setVersion(PimdirSql.version());
        canonical.close();

        assertNull(PimdirDb.refusal(RuntimeEnvironment.getApplication()));

        store = new PimdirDb(RuntimeEnvironment.getApplication());
        db = store.getWritableDatabase();
        PimdirDb.exec(
                db,
                "UPSERT_MAIL_SUMMARY",
                PimdirSql.values("collection", "INBOX", "link_id", "m1", "subject", "two"));
        try (Cursor cursor =
                db.rawQuery("SELECT subject, invitation FROM mail_summary", null)) {
            assertTrue(cursor.moveToFirst());
            assertEquals("two", cursor.getString(0));
            assertEquals("an unknown invitation keeps the known one", "e1", cursor.getString(1));
            assertFalse(cursor.moveToNext());
        }
        store.close();
        assertNull(PimdirDb.refusal(RuntimeEnvironment.getApplication()));
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
