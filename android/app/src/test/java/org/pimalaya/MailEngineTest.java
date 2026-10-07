package org.pimalaya;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.database.Cursor;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.pimalaya.client.Mailbox;
import org.pimalaya.client.PimalayaClient;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * The staged writes every mail action is, run through the real bridge
 * and the real store.
 *
 * <p>What they pin is the half of offline-first that is easy to get
 * wrong: a staged change moves the item and leaves the base alone. The
 * base is what the source last agreed on, so it is what the next sync
 * diffs the push out of; moving it with the edit would push nothing and
 * silently drop the change.
 */
@RunWith(RobolectricTestRunner.class)
public class MailEngineTest {
    private static final String EMAIL = "jane@example.com";

    private PimdirDb pimdir;
    private MailStore store;
    private MailEngine engine;
    private String collection;

    @Before
    public void setUp() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        pimdir = new PimdirDb(context);
        store = new MailStore(context, pimdir);
        store.replaceMailboxes(EMAIL, List.of(new Mailbox("INBOX", "")));
        collection = store.collectionOf(EMAIL, "INBOX");

        // No account: the shape a driver that only stages runs with, which
        // is every action in the reader and the composer.
        engine =
                new MailEngine(
                        pimdir,
                        new PimalayaClient(),
                        null,
                        new PimdirAccount(context).idOf(EMAIL));

        // One message the sync has already reconciled: bound, and agreed at
        // a flag set the server also holds.
        JSONObject write = new JSONObject();
        write.put("op", "write");
        write.put(
                "writes",
                new JSONArray()
                        .put(
                                new JSONObject()
                                        .put("op", "upsert")
                                        .put(
                                                "placement",
                                                new JSONObject()
                                                        .put("collection", collection)
                                                        .put("handle", "42")
                                                        .put("linkId", "42")
                                                        .put("level", "meta")
                                                        .put("status", "clean")
                                                        .put(
                                                                "flags",
                                                                new JSONArray().put("$junk"))
                                                        .put(
                                                                "base",
                                                                new JSONObject()
                                                                        .put(
                                                                                "flags",
                                                                                new JSONArray()
                                                                                        .put(
                                                                                                "$junk"))))));
        assertEquals("{}", engine.serve(write.toString()));
    }

    private String scalar(String sql, String... args) {
        try (Cursor cursor = pimdir.getReadableDatabase().rawQuery(sql, args)) {
            return cursor.moveToFirst() && !cursor.isNull(0) ? cursor.getString(0) : null;
        }
    }

    @Test
    public void aStagedMarkerMovesTheItemAndLeavesTheBaseAlone() throws Exception {
        JSONArray staged =
                MailEngine.withFlag(store.flagsOf(collection, "42"), MailEngine.SEEN, true);
        engine.mutateFlags(collection, "42", staged);

        JSONArray held = store.flagsOf(collection, "42");
        assertTrue("the marker the reader moved", MailEngine.has(held, MailEngine.SEEN));
        assertTrue("and the keyword this app never models", MailEngine.has(held, "$junk"));

        // The base is what the source last agreed on. If the stage moved it
        // too, the next sync would diff nothing out and the marker would
        // never reach the server.
        JSONArray base = engine.offline.baseFlags(collection, "42");
        assertFalse(MailEngine.has(base, MailEngine.SEEN));
        assertTrue(MailEngine.has(base, "$junk"));
    }

    /**
     * The staged marker has to come back to the engine as a pending push,
     * or the sync finds nothing to send: what the merge acts on is the
     * placement's status, and the store derives it rather than storing it.
     */
    @Test
    public void aStagedMarkerIsProjectedAsAPendingPush() throws Exception {
        engine.mutateFlags(
                collection,
                "42",
                MailEngine.withFlag(store.flagsOf(collection, "42"), MailEngine.SEEN, true));

        JSONObject load = new JSONObject();
        load.put("op", "load");
        load.put("collection", collection);
        JSONArray placements =
                new JSONObject(engine.serve(load.toString())).getJSONArray("placements");

        assertEquals(1, placements.length());
        assertEquals("dirty", placements.getJSONObject(0).getString("status"));
    }

    /**
     * And a message the reader opened is not one: a message is immutable,
     * so the bytes stored by opening it owe no upload, and reading them as
     * one would derive an update per opened message that no mail backend
     * would take.
     */
    @Test
    public void aStoredBodyIsNotAPendingPush() throws Exception {
        store.saveSource(collection, "42", "From: a@b.c\r\n\r\nbody\r\n".getBytes(StandardCharsets.UTF_8));

        JSONObject load = new JSONObject();
        load.put("op", "load");
        load.put("collection", collection);
        JSONArray placements =
                new JSONObject(engine.serve(load.toString())).getJSONArray("placements");

        assertEquals("clean", placements.getJSONObject(0).getString("status"));
    }

    /**
     * An opened message holds a body its base does not, which the engine
     * reads as a content edit; the mail sync pushes no content, so a marker
     * staged on it still goes out as a marker rather than as an update.
     */
    @Test
    public void aMarkerOnAnOpenedMessageIsPushedAsAMarker() throws Exception {
        store.saveSource(collection, "42", "From: a@b.c\r\n\r\nbody\r\n".getBytes(StandardCharsets.UTF_8));
        engine.mutateFlags(
                collection,
                "42",
                MailEngine.withFlag(store.flagsOf(collection, "42"), MailEngine.FLAGGED, true));

        List<String> pushed = new ArrayList<>();
        PimdirEngine remote =
                new PimdirEngine(pimdir, new PimalayaClient()) {
                    @Override
                    protected JSONObject enumerate(JSONObject yielded) throws JSONException {
                        JSONObject item =
                                new JSONObject()
                                        .put("handle", "42")
                                        .put("flags", new JSONArray().put("$junk"));
                        return new JSONObject()
                                .put("items", new JSONArray().put(item))
                                .put("vanished", new JSONArray())
                                .put("complete", true);
                    }

                    @Override
                    protected JSONObject fetch(JSONObject yielded) {
                        return new JSONObject();
                    }

                    @Override
                    protected JSONObject push(JSONObject yielded) throws JSONException {
                        JSONObject change = yielded.getJSONArray("changes").getJSONObject(0);
                        pushed.add(change.getString("op"));
                        return new JSONObject()
                                .put(
                                        "results",
                                        new JSONArray()
                                                .put(result(change.getString("handle"), true, null, null)));
                    }
                };
        new PimalayaClient().offlineSyncImmutable(remote, collection, null, false);

        assertEquals(List.of("setFlags"), pushed);
    }

    @Test
    public void aStagedDeleteIsATombstoneUntilItIsPushed() throws Exception {
        engine.mutateRemove(collection, "42");

        // Marked rather than dropped: the row has to survive until a sync
        // has told the server, or the delete would happen only here.
        assertEquals("1", scalar("SELECT deleted FROM items WHERE link_id = ?", "42"));
        assertTrue("and it leaves the list at once", store.loadMerged(10).isEmpty());
    }

    @Test
    public void anOutgoingMessageIsQueuedRatherThanStaged() {
        String source = "From: jane@example.com\r\nSubject: Hi\r\n\r\nthe body\r\n";
        String date = "Mon, 5 Jan 2026 09:00:00 +0000";

        store.queueSubmission(
                EMAIL, "queued@example.com", "Hi", date, source.getBytes(StandardCharsets.UTF_8));

        // No item: a message on its way out is an action the engine never
        // sees, which is why nothing it stages has to be kept out of a
        // reconcile or held back from a roster replace.
        assertEquals(
                "0",
                scalar("SELECT count(*) FROM items WHERE collection = ?", store.outboxOf(EMAIL)));

        List<MailStore.Outgoing> waiting = store.outgoing(EMAIL);
        assertEquals(1, waiting.size());
        assertEquals(source, new String(waiting.get(0).source, StandardCharsets.UTF_8));

        MailStore.StoredMessage row = store.loadMerged(10).get(0);
        assertTrue("it says of itself that it has not gone yet", row.pending);
        assertEquals("Hi", row.subject);
        assertEquals(store.outboxOf(EMAIL), row.collection);
    }

    /** One named member of a listed page, as a mail connector answers it. */
    private static JSONObject named(String handle, String date) throws JSONException {
        return new JSONObject()
                .put("handle", handle)
                .put("flags", new JSONArray())
                .put("linkId", handle)
                .put(
                        "summary",
                        PimdirSummary.mail(
                                null, "Subject " + handle, "", "a@example.org", null, date, 0,
                                false))
                .put("sortKey", PimdirSummary.mailSortKey(date));
    }

    /**
     * A mailbox listed in pages lands each page as it comes, and a pass cut
     * off between two pages resumes from the cursor the last landed page
     * left rather than from the top (pimdir SYNC section 5).
     */
    @Test
    public void aRoundLandsPageByPageAndResumesWhereItStopped() throws Exception {
        String since = "2026-01-01T00:00:00Z";
        List<JSONObject> asked = new ArrayList<>();
        boolean[] cut = {true};
        PimdirEngine remote =
                new PimdirEngine(pimdir, new PimalayaClient()) {
                    @Override
                    protected boolean listingsNamed() {
                        return true;
                    }

                    @Override
                    protected JSONObject enumerate(JSONObject yielded) throws JSONException {
                        asked.add(yielded);
                        JSONObject listing = yielded.getJSONObject("listing");
                        if ("delta".equals(listing.getString("kind"))) {
                            return new JSONObject()
                                    .put("items", new JSONArray())
                                    .put("vanished", new JSONArray())
                                    .put("complete", false)
                                    .put("checkpoint", "cp-2");
                        }
                        if (listing.isNull("cursor")) {
                            return new JSONObject()
                                    .put(
                                            "items",
                                            new JSONArray()
                                                    .put(named("101", "2026-10-07T08:00:00Z"))
                                                    .put(named("100", "2026-10-06T08:00:00Z")))
                                    .put("vanished", new JSONArray())
                                    .put("complete", true)
                                    .put("last", false)
                                    .put("cursor", "7:100")
                                    .put("checkpoint", "cp-1");
                        }
                        if (cut[0]) {
                            cut[0] = false;
                            throw new IllegalStateException("the connection dropped");
                        }
                        return new JSONObject()
                                .put(
                                        "items",
                                        new JSONArray().put(named("99", "2026-02-01T08:00:00Z")))
                                .put("vanished", new JSONArray())
                                .put("complete", true)
                                .put("last", true);
                    }

                    @Override
                    protected JSONObject fetch(JSONObject yielded) {
                        return new JSONObject();
                    }

                    @Override
                    protected JSONObject push(JSONObject yielded) throws JSONException {
                        return new JSONObject().put("results", new JSONArray());
                    }
                };

        try {
            new PimalayaClient().offlineSyncImmutable(remote, collection, since, false);
            throw new AssertionError("the pass reports the cut");
        } catch (org.pimalaya.client.PimalayaException cut2) {
            assertTrue(cut2.getMessage().contains("dropped"));
        }
        assertEquals(
                "the first page landed before the cut", "Subject 101",
                scalar("SELECT subject FROM mail_summary WHERE link_id = ?", "101"));
        assertEquals("2026-01-01T00:00:00Z", asked.get(0).getJSONObject("scope").getString("since"));
        assertTrue("a first pass is filling in", store.coverage(collection).filling);
        assertNull("and covers nothing yet", store.coverage(collection).at);

        new PimalayaClient().offlineSyncImmutable(remote, collection, since, false);
        JSONObject resumed = asked.get(asked.size() - 1).getJSONObject("listing");
        assertEquals("resumed, not restarted", "7:100", resumed.getString("cursor"));
        assertEquals("1", scalar("SELECT count(*) FROM items WHERE link_id = '99'"));
        // NOTE: the message the setup filed was in scope and listed by no
        // page of the round, so its close retires it.
        assertEquals("1", scalar("SELECT deleted FROM items WHERE link_id = '42'"));

        new PimalayaClient().offlineSyncImmutable(remote, collection, since, false);
        JSONObject delta = asked.get(asked.size() - 1).getJSONObject("listing");
        assertEquals("a closed round leaves a delta", "delta", delta.getString("kind"));
        assertEquals("the checkpoint taken up front", "cp-1", delta.getString("checkpoint"));
        MailStore.Coverage coverage = store.coverage(collection);
        assertEquals("mail since the bound", since, coverage.since);
        assertTrue("and complete", coverage.at != null && !coverage.filling);
    }
}
