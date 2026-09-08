package org.pimalaya;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.pimalaya.client.Card;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

import java.util.List;

/**
 * The contacts store on the pimdir tables.
 *
 * <p>These pin the translation rather than the SQL: what a staged create looks
 * like when nothing has pushed it yet, what makes an edit a pending push, what
 * membership is when the schema has no membership table, and what survives an
 * account being removed. Each of those was a column in the old schema and is a
 * relationship now, which is exactly where a migration goes wrong quietly.
 */
@RunWith(RobolectricTestRunner.class)
public class PimdirContactsTest {
    private static final String BOOK = "https://dav.example.com/books/b1/";
    private static final String OTHER = "https://dav.example.com/books/b2/";

    private PimdirDb pimdir;
    private PimdirContacts contacts;
    private SQLiteDatabase db;

    @Before
    public void setUp() {
        pimdir = new PimdirDb(RuntimeEnvironment.getApplication());
        contacts = new PimdirContacts(pimdir);
        db = pimdir.getWritableDatabase();
        collection(BOOK);
        collection(OTHER);
    }

    private void collection(String id) {
        db.execSQL(
                "INSERT INTO collections(id, account, kind, name)"
                        + " VALUES(?, 'acct', 'text/vcard', ?)",
                new Object[] {id, id});
    }

    private static String vcard(String uid, String name) {
        return "BEGIN:VCARD\r\nVERSION:4.0\r\nUID:" + uid + "\r\nFN:" + name
                + "\r\nEMAIL:" + uid + "@example.org\r\nEND:VCARD\r\n";
    }

    /**
     * Pretends a sync confirmed the card, which is what a binding means.
     *
     * <p>The refcount bump is part of it: a base body is a reference like any
     * other, and a binding written without one would let the next write collect
     * the body it names.
     */
    private void bind(String collection, String linkId, String handle, String revision) {
        String object = objectOf(collection, linkId);
        db.execSQL(
                "INSERT INTO bindings(collection, link_id, source, handle, base_object,"
                        + " base_revision) VALUES(?, ?, 'server', ?, ?, ?)"
                        + " ON CONFLICT(collection, link_id, source) DO UPDATE SET"
                        + " base_object = excluded.base_object,"
                        + " base_revision = excluded.base_revision",
                new Object[] {collection, linkId, handle, object, revision});
        db.execSQL(
                "UPDATE objects SET refcount = refcount + 1 WHERE hash = ?",
                new Object[] {object});
    }

    private String objectOf(String collection, String linkId) {
        try (Cursor cursor =
                db.rawQuery(
                        "SELECT object_hash FROM items WHERE collection = ? AND link_id = ?",
                        new String[] {collection, linkId})) {
            return cursor.moveToFirst() && !cursor.isNull(0) ? cursor.getString(0) : null;
        }
    }

    private long scalar(String sql, String... args) {
        try (Cursor cursor = db.rawQuery(sql, args)) {
            return cursor.moveToFirst() ? cursor.getLong(0) : -1;
        }
    }

    @Test
    public void aSavedCardIsAPendingCreateUntilSomethingBindsIt() {
        contacts.save(BOOK, new Card("u1", null, null, vcard("u1", "Jane Doe")));

        // No binding at all is what says "the server has never seen this": the
        // engine reads such a placement as a create to push.
        assertEquals(1, scalar("SELECT count(*) FROM items WHERE collection = ?", BOOK));
        assertEquals(0, scalar("SELECT count(*) FROM bindings"));

        List<PimdirContacts.Indexed> listed = contacts.list(BOOK);
        assertEquals(1, listed.size());
        assertEquals("Jane Doe", listed.get(0).name);
        assertEquals("u1@example.org", listed.get(0).email);
        assertEquals("u1", listed.get(0).uid);
        assertEquals("the body round-trips through the blob directory",
                vcard("u1", "Jane Doe"), listed.get(0).card.vcard);
    }

    @Test
    public void anEditMovesTheBodyAndLeavesTheBaseAlone() {
        contacts.save(BOOK, new Card("u2", null, null, vcard("u2", "Bob")));
        bind(BOOK, "u2", "c2.vcf", "etag-1");
        String base = objectOf(BOOK, "u2");

        contacts.save(BOOK, new Card("u2", null, null, vcard("u2", "Bobby")));

        // Dirty is not a flag here: the item having moved past the body its
        // binding agreed on IS the pending push, so the two cannot disagree.
        assertNotEquals(base, objectOf(BOOK, "u2"));
        assertEquals("what the server last confirmed did not change", base,
                stringOf("SELECT base_object FROM bindings WHERE link_id = 'u2'"));
        assertEquals("etag-1",
                stringOf("SELECT base_revision FROM bindings WHERE link_id = 'u2'"));

        // And the old body is still pinned, because the base names it.
        assertEquals(1, scalar("SELECT count(*) FROM objects WHERE hash = ?", base));
    }

    @Test
    public void deletingASyncedCardStagesItAndDeletingAFreshOneDropsIt() {
        contacts.save(BOOK, new Card("u3", null, null, vcard("u3", "Carol")));
        contacts.save(BOOK, new Card("u4", null, null, vcard("u4", "Dan")));
        bind(BOOK, "u3", "c3.vcf", "etag-3");

        contacts.stageDelete(BOOK, "u3");
        contacts.stageDelete(BOOK, "u4");

        // The synced one has to survive as a tombstone, or the next sync has
        // nothing to tell the server about; the never-pushed one has nobody to
        // tell, so it simply goes.
        assertEquals(1, scalar("SELECT deleted FROM items WHERE link_id = 'u3'"));
        assertEquals(0, scalar("SELECT count(*) FROM items WHERE link_id = 'u4'"));
        assertTrue("neither is displayed any more", contacts.list(BOOK).isEmpty());
    }

    @Test
    public void membershipIsPlacementAndSharesOnePublicId() {
        contacts.save(BOOK, new Card("u5", null, null, vcard("u5", "Erin")));
        bind(BOOK, "u5", "c5.vcf", "etag-5");

        contacts.stageMembership(BOOK, OTHER, "u5", true);

        assertEquals(List.of(BOOK, OTHER).size(), contacts.collectionsOf("u5").size());
        assertEquals("one identity, two placements", 1,
                scalar("SELECT count(DISTINCT seq) FROM items WHERE link_id = 'u5'"));
        assertEquals("the body is stored once", 1,
                scalar("SELECT count(*) FROM objects WHERE hash = ?", objectOf(BOOK, "u5")));
        assertEquals("and every pointer at it is counted: two placements and one base", 3,
                scalar("SELECT refcount FROM objects WHERE hash = ?", objectOf(BOOK, "u5")));
        assertEquals("the new placement is unpushed", 1, scalar("SELECT count(*) FROM bindings"));
    }

    @Test
    public void aMembershipRoundTripCancelsOutInsteadOfPushingTwice() {
        contacts.save(BOOK, new Card("u6", null, null, vcard("u6", "Fay")));
        bind(BOOK, "u6", "c6.vcf", "etag-6");

        contacts.stageMembership(BOOK, OTHER, "u6", true);
        contacts.stageMembership(BOOK, OTHER, "u6", false);
        assertEquals("an unpushed addition simply goes", List.of(BOOK),
                contacts.collectionsOf("u6"));

        contacts.stageMembership(BOOK, BOOK, "u6", false);
        assertTrue(contacts.collectionsOf("u6").isEmpty());
        contacts.stageMembership(BOOK, BOOK, "u6", true);
        assertEquals("adding a staged removal back clears the flag", List.of(BOOK),
                contacts.collectionsOf("u6"));
    }

    @Test
    public void savingAConflictedCardResolvesItAgainstTheWholeObservedState() {
        contacts.save(BOOK, new Card("u7", null, null, vcard("u7", "Gil")));
        bind(BOOK, "u7", "c7.vcf", "etag-old");
        String base = objectOf(BOOK, "u7");
        String diverging = storeBody("REMOTE");
        db.execSQL(
                "UPDATE bindings SET conflicted = 1, conflict_revision = 'etag-remote',"
                        + " conflict_object = ? WHERE link_id = 'u7'",
                new Object[] {diverging});
        assertTrue(contacts.list(BOOK).get(0).conflicted);

        contacts.save(BOOK, new Card("u7", null, null, vcard("u7", "Gil resolved")));

        // The push has to be conditioned on the state the resolution merged
        // against, revision and body together, not on the one the conflict was
        // first noticed at.
        assertEquals("etag-remote",
                stringOf("SELECT base_revision FROM bindings WHERE link_id = 'u7'"));
        assertEquals(diverging,
                stringOf("SELECT base_object FROM bindings WHERE link_id = 'u7'"));
        assertEquals(0, scalar("SELECT conflicted FROM bindings WHERE link_id = 'u7'"));
        assertFalse(contacts.list(BOOK).get(0).conflicted);
        assertEquals("the body it displaced is released", 0,
                scalar("SELECT count(*) FROM objects WHERE hash = ?", base));
        assertEquals("the adopted one keeps the one pin it always had", 1,
                scalar("SELECT refcount FROM objects WHERE hash = ?", diverging));
    }

    /** Files a body as an object of the store, unreferenced, and answers its hash. */
    private String storeBody(String body) {
        String hash = PimdirHash.of(body);
        db.execSQL(
                "INSERT INTO objects(hash, size, refcount) VALUES(?, ?, 1)"
                        + " ON CONFLICT(hash) DO NOTHING",
                new Object[] {hash, body.length()});
        return hash;
    }

    @Test
    public void detachingAnAccountKeepsTheContactsAndLosesTheSync() {
        contacts.save(BOOK, new Card("u8", null, null, vcard("u8", "Hana")));
        contacts.save(OTHER, new Card("u8", null, null, vcard("u8", "Hana")));
        contacts.save(BOOK, new Card("u9", null, null, vcard("u9", "Ivan")));
        bind(BOOK, "u8", "c8.vcf", "etag-8");
        contacts.stageDelete(BOOK, "u9");
        collection("local://on-device");

        contacts.detachToLocal(List.of(BOOK, OTHER), "local://on-device");

        // A card in two of the account's books collapses to one local row, and
        // the one the user had already asked to delete does not come back.
        assertEquals(List.of("local://on-device"), contacts.collectionsOf("u8"));
        assertTrue(contacts.collectionsOf("u9").isEmpty());

        // No binding travels with it: the local book has no server, so a
        // carried base would claim an agreement with a remote that is gone.
        assertEquals(0, scalar("SELECT count(*) FROM bindings"));
        assertEquals(0, scalar("SELECT count(*) FROM collections WHERE id = ?", BOOK));
        assertEquals(1, contacts.list("local://on-device").size());
    }

    @Test
    public void aBodilessSpineIsNotShownAsANamelessContact() {
        db.execSQL(
                "INSERT INTO items(collection, link_id, seq, flags, level)"
                        + " VALUES(?, 'u10', 1, '[]', 1)",
                new Object[] {BOOK});

        // Enumerated but not hydrated yet: an empty row would read as a broken
        // contact rather than as one still arriving.
        assertTrue(contacts.list(BOOK).isEmpty());
    }

    @Test
    public void theSummaryIsWrittenSoAnotherReaderSeesTheCard() {
        contacts.save(BOOK, new Card("u11", null, null, vcard("u11", "Kim")));

        // The standard's typed row (STORAGE Annex A.2), not a convention of
        // this app's: anything else reading the store lists the card from it.
        assertEquals("Kim", stringOf("SELECT fn FROM contact_summary WHERE link_id = 'u11'"));
        assertEquals("u11", stringOf("SELECT uid FROM contact_summary WHERE link_id = 'u11'"));
        assertEquals(
                "u11@example.org",
                stringOf("SELECT address FROM item_address WHERE link_id = 'u11'"
                        + " AND role = 'email' AND position = 0"));

        // And the key it is ordered by is the casefolded name, so two writers
        // cannot interleave the same book differently.
        assertEquals("kim", stringOf("SELECT sort_key FROM items WHERE link_id = 'u11'"));
    }

    @Test
    public void aRowWithNoSummaryIsRepairedRatherThanLeftInvisible() {
        contacts.save(BOOK, new Card("u12", null, null, vcard("u12", "Lena")));

        // What a store written before the kind had a summary table holds: the
        // item and its body, and nothing another reader could list it from. No
        // sync repairs that, the bodies not having changed.
        db.execSQL("DELETE FROM contact_summary WHERE link_id = 'u12'");
        db.execSQL("UPDATE items SET sort_key = '' WHERE link_id = 'u12'");

        assertEquals(1, contacts.repairSummaries());

        assertEquals("Lena", stringOf("SELECT fn FROM contact_summary WHERE link_id = 'u12'"));
        assertEquals("lena", stringOf("SELECT sort_key FROM items WHERE link_id = 'u12'"));

        PimdirContacts.Indexed repaired = contacts.list(BOOK).get(0);
        assertEquals("Lena", repaired.name);
        assertEquals("u12", repaired.uid);
        assertEquals("u12@example.org", repaired.email);

        assertEquals("and it is a no-op once done", 0, contacts.repairSummaries());
    }

    @Test
    public void aBodilessSpineIsNotRepairedIntoAnEmptySummary() {
        db.execSQL(
                "INSERT INTO items(collection, link_id, seq, flags, level)"
                        + " VALUES(?, 'u13', 1, '[]', 1)",
                new Object[] {BOOK});

        // Nothing to derive a summary from: leave it for the hydrate pass
        // rather than writing an empty one that reads as known-nameless.
        assertEquals(0, contacts.repairSummaries());
    }

    private String stringOf(String sql) {
        try (Cursor cursor = db.rawQuery(sql, null)) {
            return cursor.moveToFirst() && !cursor.isNull(0) ? cursor.getString(0) : null;
        }
    }
}
