package org.pimalaya.mail;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.content.Context;
import android.database.Cursor;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.pimalaya.client.Mailbox;
import org.pimalaya.pimdir.PimdirBlobs;
import org.pimalaya.pimdir.PimdirDb;
import org.pimalaya.pimdir.PimdirHash;
import org.pimalaya.pimdir.PimdirItems;
import org.pimalaya.pimdir.PimdirStorage;
import org.pimalaya.pimdir.PimdirSummary;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * A body filed while a sync runs is never lost to it (body-loss-races): a
 * write merged from placements loaded before the body was filed keeps it,
 * and a body moved into the blob store ahead of the write naming it keeps
 * its file until that write has landed.
 */
@RunWith(RobolectricTestRunner.class)
public class BodyLossRacesTest {
    private static final String EMAIL = "jane@example.com";
    private static final String DATE = "2026-10-01T12:00:00Z";
    private static final byte[] SOURCE =
            "Subject: Hello\r\n\r\nbody\r\n".getBytes(StandardCharsets.UTF_8);

    private Context context;
    private PimdirDb pimdir;
    private PimdirStorage storage;
    private MailStore store;
    private PimdirBlobs blobs;
    private String inbox;

    @Before
    public void setUp() throws Exception {
        context = RuntimeEnvironment.getApplication();
        pimdir = new PimdirDb(context);
        storage = new PimdirStorage(pimdir);
        store = new MailStore(context, pimdir);
        blobs = new PimdirBlobs(pimdir.blobs());
        store.replaceMailboxes(EMAIL, List.of(new Mailbox("INBOX", "inbox")));
        inbox = store.collectionOf(EMAIL, "INBOX");
    }

    /** One listed message as a pass writes it, at meta, holding no body. */
    private JSONObject listed(String handle, JSONArray flags, JSONObject base) throws Exception {
        JSONObject placement =
                new JSONObject()
                        .put("collection", inbox)
                        .put("handle", handle)
                        .put("linkId", handle)
                        .put("level", "meta")
                        .put("status", "clean")
                        .put("flags", flags)
                        .put(
                                "summary",
                                PimdirSummary.mail(
                                        null, "Hello", "Sender", "sender@example.org", null, DATE,
                                        10, false))
                        .put("sortKey", PimdirSummary.mailSortKey(DATE))
                        .put("base", base);
        return new JSONObject().put("op", "upsert").put("placement", placement);
    }

    private static JSONObject flagsBase(JSONArray flags) throws Exception {
        return new JSONObject().put("flags", flags);
    }

    private String text(String sql, String... args) {
        try (Cursor cursor = pimdir.getReadableDatabase().rawQuery(sql, args)) {
            return cursor.moveToFirst() && !cursor.isNull(0) ? cursor.getString(0) : null;
        }
    }

    private String item(String column, String linkId) {
        return text(
                "SELECT " + column + " FROM items WHERE collection = ? AND link_id = ?",
                inbox, linkId);
    }

    private String refcount(String hash) {
        return text("SELECT refcount FROM objects WHERE hash = ?", hash);
    }

    // ---- a stale write keeps the body ---------------------------------------

    @Test
    public void aWriteLoadedBeforeTheReaderFiledTheBodyKeepsIt() throws Exception {
        JSONArray unread = new JSONArray();
        storage.applyWrites(new JSONArray().put(listed("m1", unread, flagsBase(unread))));

        // The pass loads m1 at meta, then the reader opens it while the
        // pass is on the network.
        store.saveSource(inbox, "m1", SOURCE);
        String hash = item("object_hash", "m1");
        assertEquals("2", item("level", "m1"));

        // The pass writes what it merged from the placement it loaded:
        // no body, at meta, its new markers.
        JSONArray seen = new JSONArray().put(MailEngine.SEEN);
        storage.applyWrites(new JSONArray().put(listed("m1", seen, flagsBase(seen))));

        assertEquals("still full", "2", item("level", "m1"));
        assertEquals("the body kept", hash, item("object_hash", "m1"));
        assertEquals("1", refcount(hash));
        assertTrue("its file kept", blobs.has(hash));
        assertEquals("the markers written", seen.toString(), item("flags", "m1"));
    }

    @Test
    public void aWriteLoadedBeforeAFillLandedKeepsTheBodyAndItsBase() throws Exception {
        JSONArray none = new JSONArray();
        storage.applyWrites(new JSONArray().put(listed("m1", none, flagsBase(none))));

        // A fill chunk lands the body, its base moved to it.
        String hash = PimdirHash.of(SOURCE);
        JSONObject full = listed("m1", none, flagsBase(none).put("object", hash));
        full.getJSONObject("placement").put("object", hash).put("level", "full");
        storage.applyWrites(
                new JSONArray()
                        .put(
                                new JSONObject()
                                        .put("op", "storeObject")
                                        .put("hash", hash)
                                        .put("body", new String(SOURCE, StandardCharsets.UTF_8)))
                        .put(full));
        assertEquals("item and base", "2", refcount(hash));

        // A pass that loaded m1 before the fill writes it back at meta.
        JSONArray flagged = new JSONArray().put("\\Flagged");
        storage.applyWrites(new JSONArray().put(listed("m1", flagged, flagsBase(flagged))));

        assertEquals("2", item("level", "m1"));
        assertEquals(hash, item("object_hash", "m1"));
        assertEquals("the base kept", hash,
                text("SELECT base_object FROM bindings WHERE collection = ? AND link_id = 'm1'",
                        inbox));
        assertEquals("item and base still", "2", refcount(hash));
        assertTrue(blobs.has(hash));
    }

    // ---- a body filed ahead of its write keeps its file ----------------------

    private static void writeFile(File file, byte[] bytes) throws Exception {
        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write(bytes);
        }
    }

    @Test
    public void aPinnedBodyOutlivesTheCollectorUntilReleased() throws Exception {
        String hash = PimdirHash.of(SOURCE);
        blobs.put(hash, SOURCE);
        PimdirItems.deferUnlink(List.of(hash));

        PimdirItems.pin(hash);
        new PimdirItems(pimdir).unlinkCollected();
        assertTrue("pinned, kept", blobs.has(hash));

        PimdirItems.unpin(hash);
        new PimdirItems(pimdir).unlinkCollected();
        assertFalse("released and named by no row, unlinked", blobs.has(hash));
    }

    @Test
    public void aDownloadRacingTheLastReferenceToItsBodyKeepsTheFile() throws Exception {
        JSONArray none = new JSONArray();
        storage.applyWrites(
                new JSONArray()
                        .put(listed("m1", none, flagsBase(none)))
                        .put(listed("m2", none, flagsBase(none))));
        // m1 holds the body m2 is about to download: the same bytes.
        store.saveSource(inbox, "m1", SOURCE);
        String hash = item("object_hash", "m1");

        // The download moves its copy in: the file is there already, so
        // the copy goes.
        PimdirItems.pin(hash);
        File fetched = File.createTempFile("fetched", ".eml");
        writeFile(fetched, SOURCE);
        blobs.adopt(hash, fetched);

        // Meanwhile m1's body is replaced, its last reference to the hash
        // dropped and the object collected.
        store.saveSource(inbox, "m1", "Subject: Other\r\n\r\nother\r\n".getBytes(
                StandardCharsets.UTF_8));
        assertNull("collected", refcount(hash));
        new PimdirItems(pimdir).unlinkCollected();
        assertTrue("pinned, the file stays", blobs.has(hash));

        // The download's write lands, then releases the pin.
        JSONObject full = listed("m2", none, flagsBase(none).put("object", hash));
        full.getJSONObject("placement").put("object", hash).put("level", "full");
        storage.applyWrites(
                new JSONArray()
                        .put(
                                new JSONObject()
                                        .put("op", "storeObject")
                                        .put("hash", hash)
                                        .put("size", SOURCE.length)
                                        .put("persisted", true))
                        .put(full));
        PimdirItems.unpin(hash);
        PimdirItems.deferUnlink(List.of(hash));
        new PimdirItems(pimdir).unlinkCollected();

        assertEquals("2", item("level", "m2"));
        assertEquals("item and base", "2", refcount(hash));
        assertTrue("row and file", blobs.has(hash));
    }

    @Test
    public void aPersistedBodyMissingItsFileIsRefusedAndStaysOwed() throws Exception {
        JSONArray none = new JSONArray();
        storage.applyWrites(new JSONArray().put(listed("m1", none, flagsBase(none))));

        String hash = PimdirHash.of(SOURCE);
        JSONObject full = listed("m1", none, flagsBase(none).put("object", hash));
        full.getJSONObject("placement").put("object", hash).put("level", "full");
        try {
            storage.applyWrites(
                    new JSONArray()
                            .put(
                                    new JSONObject()
                                            .put("op", "storeObject")
                                            .put("hash", hash)
                                            .put("size", SOURCE.length)
                                            .put("persisted", true))
                            .put(full));
            fail("a row without its file was written");
        } catch (RuntimeException refused) {
            // The batch rolled back.
        }

        assertEquals("still meta", "1", item("level", "m1"));
        assertNull(item("object_hash", "m1"));
        assertNull(refcount(hash));
    }
}
