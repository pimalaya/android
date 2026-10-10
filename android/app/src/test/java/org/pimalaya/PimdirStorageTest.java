package org.pimalaya;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.database.Cursor;

import io.requery.android.database.sqlite.SQLiteDatabase;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.pimalaya.client.PimalayaException;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

import java.util.List;

/**
 * io-pimdir's storage seam against a pimdir store.
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

    /** An upsert of one placement, with no base: a pending create. */
    private JSONObject upsert(String handle, String linkId, String hash, String sortKey)
            throws Exception {
        JSONObject op = new JSONObject();
        op.put("op", "upsert");
        op.put("placement", placement(handle, linkId, hash, sortKey));
        return op;
    }

    /** The same, with the base a fetch writes beside the body it read. */
    private JSONObject synced(String handle, String linkId, String hash, String sortKey)
            throws Exception {
        JSONObject placement = placement(handle, linkId, hash, sortKey);
        JSONObject base = new JSONObject();
        base.put("revision", "rev-" + linkId);
        if (hash != null) {
            base.put("object", hash);
        }
        placement.put("base", base);

        JSONObject op = new JSONObject();
        op.put("op", "upsert");
        op.put("placement", placement);
        return op;
    }

    private JSONObject placement(String handle, String linkId, String hash, String sortKey)
            throws Exception {
        JSONObject placement = new JSONObject();
        placement.put("collection", "acct/Contacts");
        placement.put("handle", handle);
        placement.put("linkId", linkId);
        if (hash != null) {
            placement.put("object", hash);
        }
        placement.put("summary", contactSummary(linkId));
        if (sortKey != null) {
            placement.put("sortKey", sortKey);
        }
        placement.put("level", "full");
        placement.put("flags", new JSONArray());
        placement.put("status", "clean");
        return placement;
    }

    /** The typed contact summary an engine write carries, as the wire spells it. */
    private JSONObject contactSummary(String uid) throws Exception {
        JSONObject row = new JSONObject();
        row.put("uid", uid);
        row.put("fn", "Alice");
        row.put(
                "addresses",
                new JSONArray()
                        .put(
                                new JSONObject()
                                        .put("role", "email")
                                        .put("position", 0)
                                        .put("address", uid + "@example.org")));
        return new JSONObject().put("contact", row);
    }

    private JSONArray batch(JSONObject... ops) {
        JSONArray writes = new JSONArray();
        for (JSONObject op : ops) {
            writes.put(op);
        }
        return writes;
    }

    private String stringOf(String sql, String... args) {
        try (Cursor cursor = db.rawQuery(sql, args)) {
            return cursor.moveToFirst() && !cursor.isNull(0) ? cursor.getString(0) : null;
        }
    }

    private long scalar(String sql, String... args) {
        try (Cursor cursor = db.rawQuery(sql, args)) {
            return cursor.moveToFirst() ? cursor.getLong(0) : -1;
        }
    }

    @Test
    public void aWrittenPlacementLoadsBackAsItself() throws Exception {
        storage.applyWrites(
                batch(storeObject("aa11", "BEGIN:VCARD"), synced("a.vcf", "uid-a", "aa11", "alice")));

        JSONObject loaded = storage.loadCollection("acct/Contacts", null);
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

    /**
     * The status is derived from the row and never stored (SYNC §3), and the
     * two that carry a push are the two that matter: without `dirty` a
     * staged edit loads back as agreed and the merge finds nothing to send,
     * and without `created` an item no source binds reads as a member the
     * remote no longer has, which a complete round retires.
     */
    @Test
    public void theStatusIsDerivedFromWhatTheRowOwes() throws Exception {
        storage.applyWrites(
                batch(storeObject("ab12", "one"), synced("s.vcf", "uid-s", "ab12", "sam")));
        assertEquals("clean", statusOf("s.vcf"));

        // A staged edit: the body moved and the base kept what was agreed,
        // which is exactly what an `Edit` mutation writes.
        JSONObject edited = synced("s.vcf", "uid-s", "ac13", "sam").getJSONObject("placement");
        edited.getJSONObject("base").put("object", "ab12");
        JSONObject edit = new JSONObject();
        edit.put("op", "upsert");
        edit.put("placement", edited);
        storage.applyWrites(batch(storeObject("ac13", "two"), edit));
        assertEquals("dirty", statusOf("s.vcf"));

        // A create nobody has agreed on: bound with no base.
        storage.applyWrites(batch(storeObject("ad14", "three"), upsert("n.vcf", "uid-n", "ad14", "nan")));
        assertEquals("created", statusOf("n.vcf"));
    }

    @Test
    public void aMarkerSetThatOnlyReorderedOwesNoPush() throws Exception {
        JSONObject placement =
                synced("f.vcf", "uid-f", null, "fay").getJSONObject("placement");
        placement.put("flags", new JSONArray().put("\\Seen").put("\\Flagged"));
        placement.getJSONObject("base")
                .put("flags", new JSONArray().put("\\Flagged").put("\\Seen"));
        JSONObject op = new JSONObject();
        op.put("op", "upsert");
        op.put("placement", placement);
        storage.applyWrites(batch(op));

        // The markers are what differ, not the order they were written in:
        // comparing the stored text would derive a push on every sync for a
        // set nobody moved.
        assertEquals("clean", statusOf("f.vcf"));
    }

    @Test
    public void aBodilessRowNeverProjectsAsFull() throws Exception {
        // Whatever the stored level claims: an item whose body a remote
        // change dropped has to project below full, or nothing refetches it.
        storage.applyWrites(batch(synced("m.vcf", "uid-m", null, "mo")));
        db.execSQL("UPDATE items SET level = 2 WHERE link_id = 'uid-m'");

        JSONObject placement =
                storage.loadCollection("acct/Contacts", null)
                        .getJSONArray("placements")
                        .getJSONObject(0);
        assertEquals("meta", placement.getString("level"));
    }

    /** The status the projection gives one handle. */
    private String statusOf(String handle) throws Exception {
        JSONArray placements =
                storage.loadCollection("acct/Contacts", null).getJSONArray("placements");
        for (int index = 0; index < placements.length(); index++) {
            if (handle.equals(placements.getJSONObject(index).getString("handle"))) {
                return placements.getJSONObject(index).getString("status");
            }
        }
        return null;
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
                storage.loadCollection("acct/Contacts", null)
                        .getJSONArray("placements")
                        .getJSONObject(0);
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
        // only holder of what the remote expunged (SPEC.md §11).
        assertEquals(1, scalar("SELECT count(*) FROM items WHERE retained_at IS NOT NULL"));
        assertEquals(1, scalar("SELECT count(*) FROM objects WHERE hash = 'aa77'"));
        assertTrue(new PimdirBlobs(store.blobs()).has("aa77"));

        // And it is invisible to the sync seam, so nothing re-derives it.
        assertEquals(
                0,
                storage.loadCollection("acct/Contacts", null)
                        .getJSONArray("placements")
                        .length());
    }

    /** A `Deleted` drop of one handle. */
    private JSONObject drop(String handle) throws Exception {
        JSONObject drop = new JSONObject();
        drop.put("op", "drop");
        drop.put("collection", "acct/Contacts");
        drop.put("handle", handle);
        return drop;
    }

    @Test
    public void aDropThenAnUpsertOfOneHandleLeavesItPresent() throws Exception {
        storage.applyWrites(batch(storeObject("bb88", "body"), upsert("h.vcf", "uid-h", "bb88", "hana")));

        // A batch applies in order (SYNC §10): the upsert after the drop
        // restores what the drop took.
        storage.applyWrites(batch(drop("h.vcf"), upsert("h.vcf", "uid-h", "bb88", "hana")));

        JSONArray placements =
                storage.loadCollection("acct/Contacts", null).getJSONArray("placements");
        assertEquals("the placement is present", 1, placements.length());
        assertEquals("h.vcf", placements.getJSONObject(0).getString("handle"));
        assertEquals(0, scalar("SELECT count(*) FROM items WHERE retained_at IS NOT NULL"));
    }

    @Test
    public void anUpsertThenADropOfOneHandleLeavesItDropped() throws Exception {
        storage.applyWrites(batch(storeObject("bb88", "body"), upsert("h.vcf", "uid-h", "bb88", "hana")));

        // The order io-pimdir writes a withdrawn pending create in: the
        // tombstone, then the drop taking its binding.
        JSONObject tombstone = upsert("h.vcf", "uid-h", "bb88", "hana");
        tombstone.getJSONObject("placement").put("status", "tombstone");
        storage.applyWrites(batch(tombstone, drop("h.vcf")));

        assertEquals(
                0,
                storage.loadCollection("acct/Contacts", null)
                        .getJSONArray("placements")
                        .length());
        assertEquals(0, scalar("SELECT count(*) FROM bindings"));
        assertEquals(1, scalar("SELECT count(*) FROM items WHERE retained_at IS NOT NULL"));
    }

    @Test
    public void anItemABatchCreatesAndDropsIsNeverStored() throws Exception {
        storage.applyWrites(
                batch(
                        storeObject("bb88", "body"),
                        upsert("h.vcf", "uid-h", "bb88", "hana"),
                        drop("h.vcf")));

        assertEquals(0, scalar("SELECT count(*) FROM items"));
        assertEquals(0, scalar("SELECT count(*) FROM objects WHERE hash = 'bb88'"));
    }

    @Test
    public void anUnnamedUpsertAfterADropContinuesTheIdentityItHeld() throws Exception {
        storage.applyWrites(batch(storeObject("bb88", "body"), upsert("h.vcf", "uid-h", "bb88", "hana")));

        // Named against the store the batch began from, as io-pimdir names it.
        JSONObject unnamed = upsert("h.vcf", "uid-h", "bb88", "hana");
        unnamed.getJSONObject("placement").remove("linkId");
        storage.applyWrites(batch(drop("h.vcf"), unnamed));

        JSONArray placements =
                storage.loadCollection("acct/Contacts", null).getJSONArray("placements");
        assertEquals(1, placements.length());
        assertEquals("uid-h", placements.getJSONObject(0).getString("linkId"));
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
                storage.loadCollection("acct/Contacts", null).getString("checkpoint"));
    }

    @Test
    public void anEmptyCollectionLoadsEmptyRatherThanFailing() throws Exception {
        JSONObject loaded = storage.loadCollection("acct/Nothing", null);

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
        assertEquals("k.vcf", storage.loadCollection("acct/Contacts", null)
                .getJSONArray("placements").getJSONObject(0).getString("handle"));
        assertEquals("raw-7",
                storage.loadCollection(PimdirStorage.phoneCollection("acct/Contacts"), null)
                        .getJSONArray("placements").getJSONObject(0).getString("handle"));
    }

    @Test
    public void anUnboundSpokeSeesTheItemUnderItsProvisionalHandle() throws Exception {
        String phone = PimdirStorage.phoneCollection("acct/Contacts");
        storage.applyWrites(
                batch(storeObject("cd34", "BODY"), upsert("l.vcf", "uid-l", "cd34", "lena")));

        // The phone has never projected this card, so it has no binding. The
        // placement still has to reach that spoke, or the projection pass would
        // have nothing to create, and it reaches it under the U+0001 handle the
        // engine names a staged create by (SYNC §2) rather than under a name
        // the phone might one day hand out itself.
        JSONObject placement =
                storage.loadCollection(phone, null).getJSONArray("placements").getJSONObject(0);

        String handle = placement.getString("handle");
        assertEquals(PimdirStorage.provisionalOf("uid-l"), handle);
        assertEquals("and it resolves back", "uid-l", PimdirStorage.nameOf(handle));
        assertEquals("uid-l", storage.loadRow(phone, handle).getString("id"));
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
    public void aConflictWaitingOnItsRemoteBodyIsHydratedRatherThanOffered() throws Exception {
        storage.applyWrites(batch(storeObject("bb02", "LOCAL"), conflicted("bb02", null)));

        // Flagged but not yet resolvable: the resolution reads three documents
        // and the diverging one has not landed, so the hydrate pass asks for it
        // and nothing is offered to the form in the meantime.
        assertNull(storage.loadConflict("acct/Contacts", "p.vcf"));
        assertEquals(List.of(), storage.loadConflicts("acct/Contacts"));
        assertEquals("the conflict is what the upgrade pass now wants",
                List.of("p.vcf"), storage.handlesBelowFull("acct/Contacts"));
    }

    @Test
    public void theEngineSuppliedRemoteBodyMakesTheConflictResolvable() throws Exception {
        storage.applyWrites(
                batch(storeObject("bb02", "LOCAL"), storeObject("bb03", "REMOTE"),
                        conflicted("bb02", "bb03")));

        List<JSONObject> conflicts = storage.loadConflicts("acct/Contacts");
        assertEquals(1, conflicts.size());
        assertEquals("etag-remote", conflicts.get(0).getString("conflictRevision"));
        assertEquals("REMOTE", conflicts.get(0).getString("remoteVcard"));

        JSONObject bodies = storage.loadConflict("acct/Contacts", "p.vcf");
        assertEquals("LOCAL", bodies.getString("local"));
        assertEquals("REMOTE", bodies.getString("remote"));
        assertEquals("no base agreed, so local is the base", "LOCAL", bodies.getString("base"));
    }

    @Test
    public void resolvingReleasesTheDivergingBody() throws Exception {
        storage.applyWrites(
                batch(storeObject("cc03", "LOCAL"), storeObject("cc04", "REMOTE"),
                        conflicted("cc03", "cc04")));

        assertEquals(1, scalar("SELECT conflicted FROM bindings WHERE link_id = 'uid-p'"));
        assertEquals(1, scalar("SELECT refcount FROM objects WHERE hash = 'cc04'"));

        // The next clean upsert is the resolution: the diverging body has no
        // reader left, so it is released rather than pinned forever.
        storage.applyWrites(batch(upsert("p.vcf", "uid-p", "cc03", "pia")));

        assertEquals(0, scalar("SELECT conflicted FROM bindings WHERE link_id = 'uid-p'"));
        assertEquals("the diverging body has no reader left", 0,
                scalar("SELECT count(*) FROM objects WHERE hash = 'cc04'"));
    }

    /**
     * An upsert of the one conflicted placement these tests share, holding the
     * staged local body and the diverging remote one the engine's upgrade pass
     * supplies, or none while it has not run.
     */
    private JSONObject conflicted(String object, String conflictObject) throws Exception {
        JSONObject placement = upsert("p.vcf", "uid-p", object, "pia").getJSONObject("placement");
        placement.put("status", "conflict");
        placement.put("conflictRevision", "etag-remote");
        if (conflictObject != null) {
            placement.put("conflictObject", conflictObject);
        }

        JSONObject op = new JSONObject();
        op.put("op", "upsert");
        op.put("placement", placement);
        return op;
    }

    @Test
    public void theConflictRevisionIsRefreshedBeforeResolving() throws Exception {
        storage.applyWrites(
                batch(storeObject("dd04", "LOCAL"), storeObject("dd05", "REMOTE"),
                        conflicted("dd04", "dd05")));

        storage.setConflictRevision("acct/Contacts", "p.vcf", "etag-new");

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

    @Test
    public void aRemovalOnOneSourceIsAStagedRemovalOnTheOther() throws Exception {
        String phone = PimdirStorage.phoneCollection("acct/Contacts");
        JSONObject projected =
                new JSONObject(upsert("raw-5", "uid-v", "ca11", "val")
                        .getJSONObject("placement").toString());
        projected.put("collection", phone);
        projected.put("base", new JSONObject().put("object", "ca11"));
        JSONObject op = new JSONObject();
        op.put("op", "upsert");
        op.put("placement", projected);
        storage.applyWrites(
                batch(storeObject("ca11", "BODY"), upsert("v.vcf", "uid-v", "ca11", "val"), op));

        // The phone deletes it: the server still holds it, so it is the
        // server's to remove, not the phone's to be handed back.
        JSONObject drop = new JSONObject();
        drop.put("op", "drop");
        drop.put("collection", phone);
        drop.put("handle", "raw-5");
        drop.put("reason", "deleted");
        storage.applyWrites(batch(drop));

        assertEquals(0, storage.loadCollection(phone, null).getJSONArray("placements").length());
        JSONObject server =
                storage.loadCollection("acct/Contacts", null)
                        .getJSONArray("placements")
                        .getJSONObject(0);
        assertEquals("tombstone", server.getString("status"));
        assertTrue(storage.pending("acct/Contacts"));

        JSONObject gone = new JSONObject(drop.toString());
        gone.put("collection", "acct/Contacts");
        gone.put("handle", "v.vcf");
        storage.applyWrites(batch(gone));
        assertEquals("the last source retires it", 1,
                scalar("SELECT count(*) FROM items WHERE link_id = 'uid-v'"
                        + " AND retained_at IS NOT NULL"));
    }

    @Test
    public void aSupersededDropRetiresNothing() throws Exception {
        storage.applyWrites(
                batch(storeObject("ba10", "body"), upsert("temp-2", "uid-u", "ba10", "ugo")));

        // A rebuilt spine drops the old handle and upserts the new one. Only a
        // deleted drop retires an item, so reading this one as a removal would
        // retain what the same batch just renumbered.
        JSONObject drop = new JSONObject();
        drop.put("op", "drop");
        drop.put("collection", "acct/Contacts");
        drop.put("handle", "temp-2");
        drop.put("reason", "superseded");
        JSONArray effects = storage.applyWrites(batch(upsert("u.vcf", "uid-u", "ba10", "ugo"), drop));

        assertEquals("a superseded drop is not a removal", 0, effects.length());
        assertEquals(0, scalar("SELECT count(*) FROM items WHERE retained_at IS NOT NULL"));
        assertEquals(
                "u.vcf",
                storage.loadCollection("acct/Contacts", null)
                        .getJSONArray("placements")
                        .getJSONObject(0)
                        .getString("handle"));
    }

    /** An upsert of a handle no listing named and no binding holds. */
    private JSONObject unnamed(String handle) throws Exception {
        JSONObject placement = new JSONObject();
        placement.put("collection", "acct/Contacts");
        placement.put("handle", handle);
        placement.put("level", "meta");
        placement.put("status", "clean");

        JSONObject op = new JSONObject();
        op.put("op", "upsert");
        op.put("placement", placement);
        return op;
    }

    @Test
    public void nothingReachesTheStoreUnnamed() throws Exception {
        // Every member a listing carries arrives named (SYNC §4), so an upsert
        // naming no identity on a handle nothing binds is refused, the batch
        // with it, rather than filed under a key the next listing un-mints.
        try {
            storage.applyWrites(batch(unnamed("p.vcf")));
            throw new AssertionError("an unnamed placement is refused");
        } catch (PimalayaException refused) {
            assertTrue(refused.getMessage().contains("Unnamed"));
        }
        assertEquals(0, scalar("SELECT count(*) FROM items"));
        assertEquals(0, scalar("SELECT count(*) FROM bindings"));
    }

    @Test
    public void aBoundHandleNeedsNoNameToBeRewritten() throws Exception {
        storage.applyWrites(
                batch(storeObject("ba20", "body"), synced("q.vcf", "uid-q", "ba20", "quinn")));

        // A flag push or a pulled deletion restates the placement by handle:
        // the binding it holds names it.
        storage.applyWrites(batch(unnamed("q.vcf")));

        assertEquals(1, scalar("SELECT count(*) FROM items"));
        assertEquals(1, scalar("SELECT count(*) FROM bindings WHERE handle = 'q.vcf'"));
        assertEquals("uid-q", stringOf("SELECT link_id FROM bindings WHERE handle = 'q.vcf'"));
    }

    /** A round op on the collection, as the engine writes it. */
    private JSONObject round(String op, String since) throws Exception {
        JSONObject write = new JSONObject();
        write.put("op", op);
        write.put("collection", "acct/Contacts");
        JSONObject scope = new JSONObject();
        if (since != null) {
            scope.put("since", since);
        }
        write.put("closeRound".equals(op) ? "coverage" : "scope", scope);
        return write;
    }

    @Test
    public void aRoundLandsPageByPageAndClosesIntoACoverage() throws Exception {
        // The first page: the round opens, its members land and are stamped,
        // and its resume cursor lands with them, the checkpoint taken up
        // front beside it (SYNC §5).
        JSONObject cursor = new JSONObject();
        cursor.put("op", "setRoundCursor");
        cursor.put("collection", "acct/Contacts");
        cursor.put("cursor", "7:500");
        cursor.put("checkpoint", "7:42");
        JSONObject stamp = new JSONObject();
        stamp.put("op", "stamp");
        stamp.put("collection", "acct/Contacts");
        stamp.put("handles", new JSONArray().put("a.vcf"));
        storage.applyWrites(
                batch(
                        round("openRound", "2026-04-01T00:00:00Z"),
                        storeObject("ca01", "one"),
                        synced("a.vcf", "uid-a", "ca01", "alice"),
                        cursor,
                        stamp));

        JSONObject loaded = storage.loadCollection("acct/Contacts", null);
        JSONObject open = loaded.getJSONObject("round");
        assertEquals("2026-04-01T00:00:00Z", open.getString("since"));
        assertEquals("7:500", open.getString("cursor"));
        assertEquals("7:42", open.getString("checkpoint"));
        assertFalse("no round closed yet, so no coverage", loaded.has("coverage"));
        assertEquals(
                "the stamped member is no absence", 0, loaded.getJSONArray("unstamped").length());

        // A member the store held from before the round and the round never
        // listed is what its last page finds absent.
        storage.applyWrites(
                batch(storeObject("ca02", "two"), synced("b.vcf", "uid-b", "ca02", "bob")));
        assertEquals(
                "b.vcf",
                storage.loadCollection("acct/Contacts", null)
                        .getJSONArray("unstamped")
                        .getString(0));

        storage.applyWrites(batch(round("closeRound", "2026-04-01T00:00:00Z")));
        JSONObject closed = storage.loadCollection("acct/Contacts", null);
        assertFalse(closed.has("round"));
        assertEquals("7:42", closed.getString("checkpoint"));
        assertEquals("2026-04-01T00:00:00Z", closed.getJSONObject("coverage").getString("since"));
        assertTrue(closed.getJSONObject("coverage").has("at"));
    }

    @Test
    public void aBandRoundFindsNoUndatedMemberAbsent() throws Exception {
        // A member with no date (a card holds none) bound from before.
        storage.applyWrites(
                batch(storeObject("ca04", "one"), synced("u.vcf", "uid-u", "ca04", "undated")));

        // A band round lists by a date filter that never returns it, so its
        // absence there proves nothing (SYNC §5); the round records its kind
        // and the load hands it back for a resume.
        JSONObject band = round("openRound", "2026-04-01T00:00:00Z");
        band.put("band", true);
        storage.applyWrites(batch(band));
        JSONObject loaded = storage.loadCollection("acct/Contacts", null);
        assertTrue(loaded.getJSONObject("round").getBoolean("band"));
        assertEquals(
                "an undated member is left to a whole-scope round",
                0,
                loaded.getJSONArray("unstamped").length());

        storage.applyWrites(batch(round("closeRound", "2026-04-01T00:00:00Z")));
        assertEquals("a closed round carries no kind", 0, scalar("SELECT round_band FROM sources"));

        // A round over the whole scope does find it absent.
        storage.applyWrites(batch(round("openRound", null)));
        loaded = storage.loadCollection("acct/Contacts", null);
        assertFalse(loaded.getJSONObject("round").getBoolean("band"));
        assertEquals("u.vcf", loaded.getJSONArray("unstamped").getString(0));
    }

    @Test
    public void aLoadNamingNoHandleReadsTheSyncStateAlone() throws Exception {
        storage.applyWrites(
                batch(storeObject("ca03", "one"), synced("a.vcf", "uid-a", "ca03", "alice")));

        JSONObject scope = new JSONObject();
        scope.put("kind", "handles");
        scope.put("handles", new JSONArray());
        JSONObject loaded = storage.loadCollection("acct/Contacts", scope);

        // What a sync reads before it lists: a 100k-message mailbox read
        // whole to learn its checkpoint would be the first page's whole cost.
        assertEquals(0, loaded.getJSONArray("placements").length());
    }

    @Test
    public void aListedMemberIsNamedByWhatTheStoreHolds() throws Exception {
        storage.applyWrites(
                batch(storeObject("ba24", "body"), synced("h1.vcf", "uid-h1", "ba24", "hana")));

        java.util.Map<String, String[]> bound =
                storage.bound("acct/Contacts", List.of("h1.vcf", "h2.vcf"));
        assertEquals(1, bound.size());
        assertEquals("uid-h1", bound.get("h1.vcf")[0]);
        assertEquals("rev-uid-h1", bound.get("h1.vcf")[1]);
    }

    @Test
    public void aHandleNamingAnotherIdentityRetiresTheBindingItHeld() throws Exception {
        storage.applyWrites(
                batch(storeObject("ba21", "one"), upsert("r.vcf", "uid-r1", "ba21", "rita")));

        // The resource was replaced in place: the same href, another card. A
        // handle names one item per source, so the binding it held is retired
        // rather than doubled, which the store's unique index would refuse.
        storage.applyWrites(
                batch(storeObject("ba22", "two"), upsert("r.vcf", "uid-r2", "ba22", "rosa")));

        assertEquals(1, scalar("SELECT count(*) FROM bindings WHERE handle = 'r.vcf'"));
        assertEquals(
                "uid-r2",
                stringOf("SELECT link_id FROM bindings WHERE handle = 'r.vcf'"));
    }

    @Test
    public void aRekeyedDropRetiresNothingEither() throws Exception {
        storage.applyWrites(
                batch(storeObject("ba11", "body"), upsert("old-1", "uid-w", "ba11", "wes")));

        // The third reason a drop carries (SYNC §8): a handle a rebuild
        // renumbered. Only a deleted drop retires an item, so reading this one
        // as a removal would propagate a delete to the collection's other
        // sources over a handle space that merely moved.
        JSONObject drop = new JSONObject();
        drop.put("op", "drop");
        drop.put("collection", "acct/Contacts");
        drop.put("handle", "old-1");
        drop.put("reason", "rekeyed");
        JSONArray effects = storage.applyWrites(batch(upsert("new-1", "uid-w", "ba11", "wes"), drop));

        assertEquals("a rekeyed drop is not a removal", 0, effects.length());
        assertEquals(0, scalar("SELECT count(*) FROM items WHERE retained_at IS NOT NULL"));
        assertEquals(
                "new-1",
                storage.loadCollection("acct/Contacts", null)
                        .getJSONArray("placements")
                        .getJSONObject(0)
                        .getString("handle"));
    }

    @Test
    public void anUpsertWritesTheTypedSummaryAndKeepsItWhenItRestatesNone() throws Exception {
        storage.applyWrites(
                batch(storeObject("ba12", "body"), upsert("x.vcf", "uid-x", "ba12", "xena")));

        assertEquals(1, scalar("SELECT count(*) FROM contact_summary WHERE link_id = 'uid-x'"));
        assertEquals(
                1,
                scalar(
                        "SELECT count(*) FROM item_address WHERE link_id = 'uid-x'"
                                + " AND role = 'email' AND position = 0"));

        // A flag push carries no summary, and a write that does not restate one
        // keeps it, exactly as the sort key does: blanking it would strip every
        // row a sync touched of what a listing renders.
        JSONObject placement = upsert("x.vcf", "uid-x", "ba12", "xena").getJSONObject("placement");
        placement.remove("summary");
        JSONObject op = new JSONObject();
        op.put("op", "upsert");
        op.put("placement", placement);
        storage.applyWrites(batch(op));

        assertEquals(1, scalar("SELECT count(*) FROM contact_summary WHERE link_id = 'uid-x'"));
    }

    @Test
    public void aLookupAnswersTheSizeBesideTheHash() throws Exception {
        db.execSQL(
                "INSERT INTO collections(id, account, kind, name)"
                        + " VALUES('acct/INBOX', 'acct', 'message/rfc822', 'INBOX')");
        db.execSQL("INSERT INTO objects(hash, size, refcount) VALUES('ba13', 7, 1)");
        db.execSQL(
                "INSERT INTO items(collection, link_id, seq, object_hash, level)"
                        + " VALUES('acct/INBOX', 'mid-1', 1, 'ba13', 2)");
        new PimdirBlobs(store.blobs()).put("ba13", "message".getBytes("UTF-8"));

        JSONObject objects =
                storage.lookupObjects(new JSONArray().put("mid-1")).getJSONObject("objects");

        // The size is the witness an immutable link needs (SYNC §6): the engine
        // records the object it dedups against without ever reading its bytes.
        assertEquals("ba13", objects.getJSONObject("mid-1").getString("hash"));
        assertEquals(7, objects.getJSONObject("mid-1").getInt("size"));
    }

    @Test
    public void oneIdentityUnderTwoHandlesIsRefusedRatherThanRepointed() throws Exception {
        storage.applyWrites(
                batch(storeObject("bb11", "body"), upsert("v1.vcf", "uid-v", "bb11", "vera")));

        // The same identity arrives under a second handle with nothing
        // superseding the first: a double delivery, a retried append, a
        // restore. Repointing the binding would destroy the only evidence the
        // source holds it twice, and a later delete of the bound copy would
        // then remove the only copy on a source nobody touched. The second copy
        // is an item of its own under a key the engine mints (SPEC.md §9),
        // which is what makes refusing a complete answer.
        try {
            storage.applyWrites(batch(upsert("v2.vcf", "uid-v", "bb11", "vera")));
            throw new AssertionError("the repointing write was applied");
        } catch (PimalayaException refused) {
            assertTrue(refused.getMessage(), refused.getMessage().contains("v1.vcf"));
        }

        JSONArray placements =
                storage.loadCollection("acct/Contacts", null).getJSONArray("placements");
        assertEquals(1, placements.length());
        assertEquals("the bound handle stays",
                "v1.vcf", placements.getJSONObject(0).getString("handle"));
    }

    @Test
    public void aScopedLoadAnswersTheRowsItWasAskedFor() throws Exception {
        storage.applyWrites(batch(
                storeObject("cc12", "one"), upsert("w.vcf", "uid-w", "cc12", "wendy"),
                storeObject("cc13", "two"), upsert("x.vcf", "uid-x", "cc13", "xena")));

        JSONObject byHandle = new JSONObject()
                .put("kind", "handles")
                .put("handles", new JSONArray().put("x.vcf"));
        JSONArray narrowed =
                storage.loadCollection("acct/Contacts", byHandle).getJSONArray("placements");
        assertEquals(1, narrowed.length());
        assertEquals("x.vcf", narrowed.getJSONObject(0).getString("handle"));

        JSONObject byLink = new JSONObject()
                .put("kind", "links")
                .put("links", new JSONArray().put("uid-w"));
        narrowed = storage.loadCollection("acct/Contacts", byLink).getJSONArray("placements");
        assertEquals(1, narrowed.length());
        assertEquals("w.vcf", narrowed.getJSONObject(0).getString("handle"));

        // The scope is a floor, so an unnarrowed read still answers everything.
        JSONObject all = new JSONObject().put("kind", "all");
        assertEquals(
                2, storage.loadCollection("acct/Contacts", all).getJSONArray("placements").length());
    }

    @Test
    public void unreadMarkersStayUnreadAcrossAWrite() throws Exception {
        JSONObject placement =
                upsert("y.vcf", "uid-y", "cc14", "yuri").getJSONObject("placement");
        placement.remove("flags");
        JSONObject op = new JSONObject();
        op.put("op", "upsert");
        op.put("placement", placement);
        storage.applyWrites(batch(storeObject("cc14", "body"), op));

        // Absent means nobody has read the markers, which the column says as
        // NULL. Storing an empty set instead would turn that into an
        // authoritative "carries none" and clear whatever the other side knew.
        JSONObject loaded =
                storage.loadCollection("acct/Contacts", null)
                        .getJSONArray("placements")
                        .getJSONObject(0);
        assertFalse(loaded.has("flags"));

        storage.applyWrites(batch(upsert("y.vcf", "uid-y", "cc14", "yuri")));
        loaded = storage.loadCollection("acct/Contacts", null)
                .getJSONArray("placements")
                .getJSONObject(0);
        assertEquals(0, loaded.getJSONArray("flags").length());
    }

    @Test
    public void anAgreedBaseWithNoValuesSurvivesTheRoundTrip() throws Exception {
        JSONObject placement =
                upsert("z.vcf", "uid-z", "cc15", "zoe").getJSONObject("placement");
        placement.put("base", new JSONObject());
        JSONObject op = new JSONObject();
        op.put("op", "upsert");
        op.put("placement", placement);
        storage.applyWrites(batch(storeObject("cc15", "body"), op));

        // A base of no revision, no body and markers nobody has read is a real
        // agreement its three value columns cannot express: reading presence off
        // them alone has the placement come back as never-agreed, and the sync
        // re-derives the same push on every run.
        JSONObject loaded =
                storage.loadCollection("acct/Contacts", null)
                        .getJSONArray("placements")
                        .getJSONObject(0);
        assertTrue(loaded.has("base"));
        assertEquals(0, loaded.getJSONObject("base").length());
    }

    @Test
    public void aStagedRemovalIsLoadedBackAsTheTombstoneItIs() throws Exception {
        storage.applyWrites(batch(storeObject("dd16", "body"), upsert("t.vcf", "uid-t", "dd16", "t")));

        JSONObject placement =
                upsert("t.vcf", "uid-t", "dd16", "t").getJSONObject("placement");
        placement.put("status", "tombstone");
        JSONObject op = new JSONObject();
        op.put("op", "upsert");
        op.put("placement", placement);
        storage.applyWrites(batch(op));

        assertEquals(1, scalar("SELECT deleted FROM items WHERE link_id = ?", "uid-t"));

        // The merge derives the remove push from the placement, so a load that
        // hid it would leave the delete local forever and let the next
        // enumerate read the member the server still holds as one to add back.
        JSONArray placements =
                storage.loadCollection("acct/Contacts", null).getJSONArray("placements");
        assertEquals(1, placements.length());
        assertEquals("tombstone", placements.getJSONObject(0).getString("status"));

        // And an edit after it revives the row, which is what makes an edit
        // beat a delete rather than the order of two taps deciding.
        storage.applyWrites(batch(upsert("t.vcf", "uid-t", "dd16", "t")));
        assertEquals(0, scalar("SELECT deleted FROM items WHERE link_id = ?", "uid-t"));
    }
}
