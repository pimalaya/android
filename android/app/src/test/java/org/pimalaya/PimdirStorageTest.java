package org.pimalaya;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

import java.util.List;

/**
 * io-replica's storage seam against a pimdir store.
 *
 * <p>These drive the seam the way the engine does: JSON envelopes in, JSON out,
 * so what is checked is the contract rather than the SQL. The cases that matter
 * are the ones where the pimdir schema changes behaviour: bodies leaving the
 * database for content-addressed blobs, a drop retaining rather than deleting,
 * a sort key surviving a write that does not restate it, and refcounts moving
 * by the difference a placement made.
 */
@RunWith(RobolectricTestRunner.class)
public class PimdirStorageTest {
    private PimdirDb store;
    private PimdirStorage storage;
    private SQLiteDatabase db;

    @Before
    public void setUp() {
        store = new PimdirDb(RuntimeEnvironment.getApplication());
        storage = new PimdirStorage(store);
        db = store.getWritableDatabase();
        db.execSQL(
                "INSERT INTO collections(id, account, kind, name)"
                        + " VALUES('acct/Contacts', 'acct', 'text/vcard', 'Contacts')");
    }

    /** A store-object write for a body the engine already hashed. */
    private JSONObject storeObject(String hash, String body) throws Exception {
        JSONObject op = new JSONObject();
        op.put("op", "storeObject");
        op.put("hash", hash);
        op.put("body", body);
        return op;
    }

    /** An upsert of one placement. */
    private JSONObject upsert(String handle, String linkId, String hash, String sortKey)
            throws Exception {
        JSONObject placement = new JSONObject();
        placement.put("collection", "acct/Contacts");
        placement.put("handle", handle);
        placement.put("linkId", linkId);
        if (hash != null) {
            placement.put("object", hash);
        }
        placement.put("meta", PimdirMeta.contact(new JSONObject().put("uid", linkId), 12));
        if (sortKey != null) {
            placement.put("sortKey", sortKey);
        }
        placement.put("level", "full");
        placement.put("flags", new JSONArray());
        placement.put("status", "clean");

        JSONObject op = new JSONObject();
        op.put("op", "upsert");
        op.put("placement", placement);
        return op;
    }

    private JSONArray batch(JSONObject... ops) {
        JSONArray writes = new JSONArray();
        for (JSONObject op : ops) {
            writes.put(op);
        }
        return writes;
    }

    private long scalar(String sql, String... args) {
        try (Cursor cursor = db.rawQuery(sql, args)) {
            return cursor.moveToFirst() ? cursor.getLong(0) : -1;
        }
    }

    @Test
    public void aWrittenPlacementLoadsBackAsItself() throws Exception {
        storage.applyWrites(
                batch(storeObject("aa11", "BEGIN:VCARD"), upsert("a.vcf", "uid-a", "aa11", "alice")));

        JSONObject loaded = storage.loadCollection("acct/Contacts");
        JSONArray placements = loaded.getJSONArray("placements");
        assertEquals(1, placements.length());

        JSONObject placement = placements.getJSONObject(0);
        assertEquals("a.vcf", placement.getString("handle"));
        assertEquals("uid-a", placement.getString("linkId"));
        assertEquals("aa11", placement.getString("object"));
        assertEquals("alice", placement.getString("sortKey"));
        assertEquals("full", placement.getString("level"));
        assertEquals("clean", placement.getString("status"));
    }

    @Test
    public void theBodyLivesInABlobNotInTheDatabase() throws Exception {
        storage.applyWrites(
                batch(storeObject("bb22", "BEGIN:VCARD\r\nEND:VCARD"), upsert("b.vcf", "uid-b", "bb22", "bob")));

        PimdirBlobs blobs = new PimdirBlobs(store.blobs());
        assertEquals("BEGIN:VCARD\r\nEND:VCARD", blobs.getText("bb22"));

        // The row references the body; it does not contain it.
        assertEquals(1, scalar("SELECT count(*) FROM objects WHERE hash = 'bb22'"));
        assertEquals(1, scalar("SELECT refcount FROM objects WHERE hash = 'bb22'"));
    }

    @Test
    public void aWriteThatDoesNotRestateTheKeyPreservesIt() throws Exception {
        storage.applyWrites(batch(storeObject("cc33", "one"), upsert("c.vcf", "uid-c", "cc33", "carol")));

        // The reference write is a replace-all, so an upsert carrying no key
        // must leave the stored one alone (SPEC.md §9.3). Blanking it here would
        // silently reset the ordering of every item a sync touched.
        storage.applyWrites(batch(storeObject("dd44", "two"), upsert("c.vcf", "uid-c", "dd44", null)));

        JSONObject placement =
                storage.loadCollection("acct/Contacts").getJSONArray("placements").getJSONObject(0);
        assertEquals("carol", placement.getString("sortKey"));
        assertEquals("dd44", placement.getString("object"));
    }

    @Test
    public void refcountsMoveByTheDifferenceAndOrphansAreCollected() throws Exception {
        storage.applyWrites(batch(storeObject("ee55", "one"), upsert("e.vcf", "uid-e", "ee55", "eve")));
        assertEquals(1, scalar("SELECT refcount FROM objects WHERE hash = 'ee55'"));

        // Re-pointing the item at a new body releases the old one, which then
        // has no reference and goes, blob and all.
        storage.applyWrites(batch(storeObject("ff66", "two"), upsert("e.vcf", "uid-e", "ff66", "eve")));

        assertEquals("the replaced body is gone", 0,
                scalar("SELECT count(*) FROM objects WHERE hash = 'ee55'"));
        assertFalse(new PimdirBlobs(store.blobs()).has("ee55"));
        assertEquals(1, scalar("SELECT refcount FROM objects WHERE hash = 'ff66'"));
    }

    @Test
    public void aDropRetainsRatherThanDeletes() throws Exception {
        storage.applyWrites(batch(storeObject("aa77", "body"), upsert("g.vcf", "uid-g", "aa77", "gina")));

        JSONObject drop = new JSONObject();
        drop.put("op", "drop");
        drop.put("collection", "acct/Contacts");
        drop.put("handle", "g.vcf");
        JSONArray effects = storage.applyWrites(batch(drop));

        assertEquals("removed", effects.getJSONObject(0).getString("kind"));

        // The row survives with its body pinned: for a backup the store is the
        // only holder of what the remote expunged (SPEC.md §16).
        assertEquals(1, scalar("SELECT count(*) FROM items WHERE retained_at IS NOT NULL"));
        assertEquals(1, scalar("SELECT count(*) FROM objects WHERE hash = 'aa77'"));
        assertTrue(new PimdirBlobs(store.blobs()).has("aa77"));

        // And it is invisible to the sync seam, so nothing re-derives it.
        assertEquals(0, storage.loadCollection("acct/Contacts").getJSONArray("placements").length());
    }

    @Test
    public void aRekeyDoesNotDeleteWhatItJustRenamed() throws Exception {
        storage.applyWrites(batch(storeObject("bb88", "body"), upsert("temp-1", "uid-h", "bb88", "hana")));

        // An accepted create drops the placeholder and upserts the assigned
        // handle in one batch. Applying those in arrival order would delete the
        // row the upsert had just renamed, so drops are held to the end and
        // cancelled by an upsert of the same placement.
        JSONObject drop = new JSONObject();
        drop.put("op", "drop");
        drop.put("collection", "acct/Contacts");
        drop.put("handle", "temp-1");
        storage.applyWrites(batch(drop, upsert("temp-1", "uid-h", "bb88", "hana")));

        JSONArray placements = storage.loadCollection("acct/Contacts").getJSONArray("placements");
        assertEquals("the rekeyed placement survived", 1, placements.length());
        assertEquals(0, scalar("SELECT count(*) FROM items WHERE retained_at IS NOT NULL"));
    }

    @Test
    public void oneLinkIdSharesOneSeqAcrossCollections() throws Exception {
        db.execSQL(
                "INSERT INTO collections(id, account, kind, name)"
                        + " VALUES('acct/Other', 'acct', 'text/vcard', 'Other')");

        storage.applyWrites(batch(storeObject("cc99", "body"), upsert("i.vcf", "uid-i", "cc99", "iris")));

        JSONObject placement = new JSONObject(upsert("i.vcf", "uid-i", "cc99", "iris")
                .getJSONObject("placement").toString());
        placement.put("collection", "acct/Other");
        JSONObject op = new JSONObject();
        op.put("op", "upsert");
        op.put("placement", placement);
        storage.applyWrites(batch(op));

        assertEquals("both placements share one public id", 1,
                scalar("SELECT count(DISTINCT seq) FROM items WHERE link_id = 'uid-i'"));
        assertEquals(2, scalar("SELECT count(*) FROM items WHERE link_id = 'uid-i'"));

        // The body is stored once and referenced twice: the refcount counts
        // pointers (SPEC.md §5), so two placements is two references to one
        // object row and one blob. Deduplication is the single copy, not a
        // count of one.
        assertEquals("one object row", 1, scalar("SELECT count(*) FROM objects WHERE hash = 'cc99'"));
        assertEquals("referenced twice", 2, scalar("SELECT refcount FROM objects WHERE hash = 'cc99'"));
    }

    @Test
    public void contactsNeverDedupeBodiesAcrossReplicas() throws Exception {
        storage.applyWrites(batch(storeObject("dd00", "body"), upsert("j.vcf", "uid-j", "dd00", "jane")));

        // The engine's dedup assumes a link id names immutable bytes. That holds
        // for a message and not for a card: two replicas sharing a vCard UID
        // diverge legitimately, so one body must never stand in for another's.
        JSONArray links = new JSONArray();
        links.put("uid-j");
        assertEquals(0, storage.lookupObjects(links).getJSONObject("objects").length());
    }

    @Test
    public void aCheckpointRoundTrips() throws Exception {
        JSONObject op = new JSONObject();
        op.put("op", "setCheckpoint");
        op.put("collection", "acct/Contacts");
        op.put("checkpoint", "sync-token-42");
        storage.applyWrites(batch(op));

        assertEquals("sync-token-42",
                storage.loadCollection("acct/Contacts").getString("checkpoint"));
    }

    @Test
    public void anEmptyCollectionLoadsEmptyRatherThanFailing() throws Exception {
        JSONObject loaded = storage.loadCollection("acct/Nothing");

        assertEquals(0, loaded.getJSONArray("placements").length());
        assertFalse("no checkpoint yet", loaded.has("checkpoint"));
    }

    // ---- the two spokes ---------------------------------------------------

    @Test
    public void theTwoSpokesShareOneItemAndBindSeparately() throws Exception {
        storage.applyWrites(
                batch(storeObject("ab12", "SERVER"), upsert("k.vcf", "uid-k", "ab12", "kim")));

        // The phone spoke names the same book through its own collection id,
        // and the store answers from the same item with the phone's binding.
        JSONObject phone = new JSONObject(upsert("raw-7", "uid-k", "ab12", "kim")
                .getJSONObject("placement").toString());
        phone.put("collection", PimdirStorage.phoneCollection("acct/Contacts"));
        JSONObject op = new JSONObject();
        op.put("op", "upsert");
        op.put("placement", phone);
        storage.applyWrites(batch(op));

        assertEquals("one item, not two", 1,
                scalar("SELECT count(*) FROM items WHERE link_id = 'uid-k'"));
        assertEquals("one binding per source", 2,
                scalar("SELECT count(*) FROM bindings WHERE link_id = 'uid-k'"));

        // Each spoke sees its own handle for it.
        assertEquals("k.vcf", storage.loadCollection("acct/Contacts")
                .getJSONArray("placements").getJSONObject(0).getString("handle"));
        assertEquals("raw-7",
                storage.loadCollection(PimdirStorage.phoneCollection("acct/Contacts"))
                        .getJSONArray("placements").getJSONObject(0).getString("handle"));
    }

    @Test
    public void anUnboundSpokeSeesTheItemUnderItsLinkId() throws Exception {
        storage.applyWrites(
                batch(storeObject("cd34", "BODY"), upsert("l.vcf", "uid-l", "cd34", "lena")));

        // The phone has never projected this card, so it has no binding. The
        // placement still has to reach that spoke, or the projection pass
        // would have nothing to create.
        JSONObject placement =
                storage.loadCollection(PimdirStorage.phoneCollection("acct/Contacts"))
                        .getJSONArray("placements").getJSONObject(0);

        assertEquals("uid-l", placement.getString("handle"));
        assertFalse("nothing agreed with the phone yet", placement.has("base"));
    }

    // ---- the driver's own reads -------------------------------------------

    @Test
    public void aRowCarriesWhatAPushIsConditionedOn() throws Exception {
        JSONObject placement = upsert("m.vcf", "uid-m", "ef56", "mona").getJSONObject("placement");
        placement.put("base", new JSONObject().put("revision", "etag-9").put("object", "ef56"));
        JSONObject op = new JSONObject();
        op.put("op", "upsert");
        op.put("placement", placement);
        storage.applyWrites(batch(storeObject("ef56", "BEGIN:VCARD"), op));

        JSONObject row = storage.loadRow("acct/Contacts", "m.vcf");
        assertEquals("uid-m", row.getString("id"));
        assertEquals("m.vcf", row.getString("uri"));
        assertEquals("etag-9", row.getString("etag"));
        assertEquals("BEGIN:VCARD", row.getString("vcard"));
        assertEquals("BEGIN:VCARD", row.getString("baseVcard"));
        assertFalse(row.getBoolean("deleted"));

        assertNull("an unknown handle is not a row", storage.loadRow("acct/Contacts", "gone.vcf"));
    }

    @Test
    public void hydrationListsWhatIsBelowFull() throws Exception {
        JSONObject spine = upsert("n.vcf", "uid-n", null, "nils").getJSONObject("placement");
        spine.put("level", "meta");
        JSONObject op = new JSONObject();
        op.put("op", "upsert");
        op.put("placement", spine);

        storage.applyWrites(batch(op,
                storeObject("aa01", "FULL"), upsert("o.vcf", "uid-o", "aa01", "olga")));

        assertEquals("only the spine needs a body",
                List.of("n.vcf"), storage.handlesBelowFull("acct/Contacts"));
    }

    @Test
    public void aCapturedRemoteBodyMakesTheConflictResolvable() throws Exception {
        JSONObject placement = upsert("p.vcf", "uid-p", "bb02", "pia").getJSONObject("placement");
        placement.put("status", "conflict");
        placement.put("conflictRevision", "etag-remote");
        placement.put("base", new JSONObject().put("revision", "etag-base"));
        JSONObject op = new JSONObject();
        op.put("op", "upsert");
        op.put("placement", placement);
        storage.applyWrites(batch(storeObject("bb02", "LOCAL"), op));

        // Flagged but not yet resolvable: the resolution form needs the remote
        // document, and until a sync captures it there is nothing to merge.
        assertNull(storage.loadConflict("acct/Contacts", "p.vcf"));

        List<JSONObject> conflicts = storage.loadConflicts("acct/Contacts");
        assertEquals(1, conflicts.size());
        assertEquals("etag-remote", conflicts.get(0).getString("conflictRevision"));

        storage.setConflictRemote("acct/Contacts", "p.vcf", "REMOTE");
        JSONObject bodies = storage.loadConflict("acct/Contacts", "p.vcf");
        assertEquals("LOCAL", bodies.getString("local"));
        assertEquals("REMOTE", bodies.getString("remote"));
        assertEquals("no base agreed, so local is the base", "LOCAL", bodies.getString("base"));
    }

    @Test
    public void resolvingReleasesTheCapturedBody() throws Exception {
        JSONObject placement = upsert("q.vcf", "uid-q", "cc03", "quin").getJSONObject("placement");
        placement.put("status", "conflict");
        JSONObject conflicted = new JSONObject();
        conflicted.put("op", "upsert");
        conflicted.put("placement", placement);
        storage.applyWrites(batch(storeObject("cc03", "LOCAL"), conflicted));
        storage.setConflictRemote("acct/Contacts", "q.vcf", "REMOTE");

        assertEquals(1, scalar("SELECT conflicted FROM items WHERE link_id = 'uid-q'"));

        // The next clean upsert is the resolution: the captured body has no
        // reader left, so it is released rather than pinned forever.
        storage.applyWrites(batch(upsert("q.vcf", "uid-q", "cc03", "quin")));

        assertEquals(0, scalar("SELECT conflicted FROM items WHERE link_id = 'uid-q'"));
        assertEquals(0, scalar(
                "SELECT count(*) FROM objects WHERE hash = ?", PimdirHash.of("REMOTE")));
    }

    @Test
    public void theConflictRevisionIsRefreshedBeforeResolving() throws Exception {
        JSONObject placement = upsert("r.vcf", "uid-r", "dd04", "remy").getJSONObject("placement");
        placement.put("status", "conflict");
        placement.put("conflictRevision", "etag-old");
        JSONObject op = new JSONObject();
        op.put("op", "upsert");
        op.put("placement", placement);
        storage.applyWrites(batch(storeObject("dd04", "LOCAL"), op));

        storage.setConflictRevision("acct/Contacts", "r.vcf", "etag-new");

        assertEquals("etag-new",
                storage.loadConflicts("acct/Contacts").get(0).getString("conflictRevision"));
    }

    // ---- the quiet path ---------------------------------------------------

    @Test
    public void theQuietPathSkipsASpokeWithNothingToDo() throws Exception {
        String phone = PimdirStorage.phoneCollection("acct/Contacts");
        storage.applyWrites(
                batch(storeObject("ee07", "BODY"), upsert("s.vcf", "uid-s", "ee07", "sara")));

        // The phone has never seen it: that is work.
        assertTrue(storage.pending(phone));
        assertEquals(1, storage.memberCount(phone));

        JSONObject projected =
                new JSONObject(upsert("raw-9", "uid-s", "ee07", "sara")
                        .getJSONObject("placement").toString());
        projected.put("collection", phone);
        projected.put("base", new JSONObject().put("object", "ee07"));
        JSONObject op = new JSONObject();
        op.put("op", "upsert");
        op.put("placement", projected);
        storage.applyWrites(batch(op));

        // Converged: the phone agreed on the body the item still carries, so a
        // pass would reconcile nothing and is worth skipping outright.
        assertFalse(storage.pending(phone));
    }

    @Test
    public void aBodyMovingPastTheAgreedBaseIsWork() throws Exception {
        String phone = PimdirStorage.phoneCollection("acct/Contacts");
        JSONObject projected =
                new JSONObject(upsert("raw-4", "uid-t", "ff08", "tom")
                        .getJSONObject("placement").toString());
        projected.put("collection", phone);
        projected.put("base", new JSONObject().put("object", "ff08"));
        JSONObject op = new JSONObject();
        op.put("op", "upsert");
        op.put("placement", projected);
        storage.applyWrites(batch(storeObject("ff08", "ONE"), op));
        assertFalse(storage.pending(phone));

        // A server round repoints the item at a new body. The phone's base
        // still names the old one, which is the whole signal that the spoke
        // has something to project.
        storage.applyWrites(
                batch(storeObject("ab09", "TWO"), upsert("t.vcf", "uid-t", "ab09", "tom")));

        assertTrue(storage.pending(phone));
    }
}
