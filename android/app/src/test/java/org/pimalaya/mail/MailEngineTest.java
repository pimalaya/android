package org.pimalaya.mail;

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
import org.pimalaya.client.AccountClient;
import org.pimalaya.client.CalendarClient;
import org.pimalaya.client.Clients;
import org.pimalaya.client.ContactClient;
import org.pimalaya.client.Flags;
import org.pimalaya.client.MailClient;
import org.pimalaya.client.MailPage;
import org.pimalaya.client.MailRequest;
import org.pimalaya.client.MailSession;
import org.pimalaya.client.MailWire;
import org.pimalaya.client.Mailbox;
import org.pimalaya.client.MessageRef;
import org.pimalaya.client.OfflineClient;
import org.pimalaya.client.OfflineDriver;
import org.pimalaya.client.SyncReport;
import org.pimalaya.pimdir.ItemFixtures;
import org.pimalaya.pimdir.PimdirAccount;
import org.pimalaya.pimdir.PimdirDb;
import org.pimalaya.pimdir.PimdirSummary;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * The staged writes every mail action is, through the real bridge and store.
 *
 * <p>They pin what offline-first easily gets wrong: a staged change moves the
 * item, never the base. The base is what the source last agreed on, which the
 * next sync diffs the push out of: moving it would silently drop the change.
 */
@RunWith(RobolectricTestRunner.class)
public class MailEngineTest {
    private static final String EMAIL = "jane@example.com";

    private PimdirDb pimdir;
    private ItemFixtures fixtures;
    private MailStore store;
    private MailStager engine;
    private String collection;
    private String accountId;

    @Before
    public void setUp() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        pimdir = new PimdirDb(context);
        fixtures = new ItemFixtures(pimdir);
        store = new MailStore(context, pimdir);
        store.replaceMailboxes(EMAIL, List.of(new Mailbox("INBOX", "")));
        collection = store.collectionOf(EMAIL, "INBOX");
        accountId = new PimdirAccount(context).idOf(EMAIL);

        engine = new MailStager(pimdir, Clients.real());

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
        Flags staged = store.flagsOf(collection, "42").with(Flags.Marker.SEEN, true);
        engine.mutateFlags(collection, "42", staged);

        Flags held = store.flagsOf(collection, "42");
        assertTrue("the marker the reader moved", held.has(Flags.Marker.SEEN));
        assertTrue("and the keyword this app never models", held.markers().contains("$junk"));

        // NOTE: if the stage moved the base too, the next sync would diff
        // nothing out and the marker would never reach the server.
        Flags base = engine.offline.baseFlags(collection, "42");
        assertFalse(base.has(Flags.Marker.SEEN));
        assertTrue(base.markers().contains("$junk"));
    }

    /**
     * A staged marker comes back to the engine as a pending push.
     *
     * <p>Or the sync finds nothing to send: the merge acts on the placement's
     * status, which the store derives rather than stores.
     */
    @Test
    public void aStagedMarkerIsProjectedAsAPendingPush() throws Exception {
        engine.mutateFlags(
                collection,
                "42",
                store.flagsOf(collection, "42").with(Flags.Marker.SEEN, true));

        JSONObject load = new JSONObject();
        load.put("op", "load");
        load.put("collection", collection);
        JSONArray placements =
                new JSONObject(engine.serve(load.toString())).getJSONArray("placements");

        assertEquals(1, placements.length());
        assertEquals("dirty", placements.getJSONObject(0).getString("status"));
    }

    /**
     * A message the reader opened owes no upload.
     *
     * <p>A message is immutable: reading its stored bytes as an edit would
     * derive an update per opened message that no mail backend would take.
     */
    @Test
    public void aStoredBodyIsNotAPendingPush() throws Exception {
        fixtures.putObject(collection, "42", "From: a@b.c\r\n\r\nbody\r\n".getBytes(StandardCharsets.UTF_8));

        JSONObject load = new JSONObject();
        load.put("op", "load");
        load.put("collection", collection);
        JSONArray placements =
                new JSONObject(engine.serve(load.toString())).getJSONArray("placements");

        assertEquals("clean", placements.getJSONObject(0).getString("status"));
    }

    /**
     * A marker staged on an opened message goes out as a marker.
     *
     * <p>Its body, absent from the base, reads as a content edit to the engine;
     * the mail sync pushes no content.
     */
    @Test
    public void aMarkerOnAnOpenedMessageIsPushedAsAMarker() throws Exception {
        fixtures.putObject(collection, "42", "From: a@b.c\r\n\r\nbody\r\n".getBytes(StandardCharsets.UTF_8));
        engine.mutateFlags(
                collection,
                "42",
                store.flagsOf(collection, "42").with(Flags.Marker.FLAGGED, true));

        List<String> pushed = new ArrayList<>();
        MailClient server =
                new MailClient() {
                    @Override
                    public MailPage enumerateMailbox(
                            MailSession session, String mailbox, MailRequest request) {
                        try {
                            JSONObject item =
                                    new JSONObject()
                                            .put("handle", "42")
                                            .put("flags", new JSONArray().put("$junk"));
                            return MailWire.page(new JSONObject()
                                    .put("items", new JSONArray().put(item))
                                    .put("vanished", new JSONArray())
                                    .put("complete", true));
                        } catch (JSONException failure) {
                            throw new AssertionError(failure);
                        }
                    }

                    @Override
                    public void setFlag(
                            MailSession session,
                            MessageRef message,
                            Flags.Marker flag,
                            boolean add) {}
                };
        OfflineClient bridge =
                new OfflineClient() {
                    @Override
                    public SyncReport syncImmutable(
                            OfflineDriver driver,
                            String collection,
                            String since,
                            boolean scopeBound) {
                        return super.syncImmutable(
                                yielded -> {
                                    try {
                                        JSONObject asked = new JSONObject(yielded);
                                        if ("push".equals(asked.getString("op"))) {
                                            JSONObject change =
                                                    asked.getJSONArray("changes")
                                                            .getJSONObject(0);
                                            pushed.add(change.getString("op"));
                                        }
                                    } catch (JSONException failure) {
                                        throw new AssertionError(failure);
                                    }
                                    return driver.serve(yielded);
                                },
                                collection,
                                since,
                                scopeBound);
                    }
                };
        MailEngine remote =
                new MailEngine(
                        pimdir,
                        new Clients(
                                new AccountClient(), new ContactClient(), new CalendarClient(),
                                server, bridge),
                        null,
                        accountId);
        bridge.syncImmutable(remote, collection, null, false);

        assertEquals(List.of("setFlags"), pushed);
    }

    @Test
    public void aLookupAnswersTheAccountsOwnBodiesOnAnAccountWideListingAlone() throws Exception {
        store.replaceMailboxes("john@example.com", List.of(new Mailbox("INBOX", "")));
        String theirs = store.collectionOf("john@example.com", "INBOX");
        fixtures.put(
                pimdir.getWritableDatabase(),
                theirs,
                new ItemFixtures.Row("m-1", "their body", null, ""));
        fixtures.put(
                pimdir.getWritableDatabase(),
                collection,
                new ItemFixtures.Row("m-2", "our body", null, ""));
        MailEngine driver = new MailEngine(pimdir, Clients.real(), null, accountId);
        JSONArray links = new JSONArray().put("m-1").put("m-2");

        // NOTE: an IMAP UID or a Graph id names one mailbox's message.
        MailPolicy.markAccountWide(pimdir.context(), accountId, false);
        assertEquals(0, driver.lookup(links).getJSONObject("objects").length());

        MailPolicy.markAccountWide(pimdir.context(), accountId, true);
        JSONObject objects = driver.lookup(links).getJSONObject("objects");
        assertEquals("another account's is no fact", 1, objects.length());
        assertTrue(objects.has("m-2"));
    }

    @Test
    public void aStagedDeleteIsATombstoneUntilItIsPushed() throws Exception {
        engine.mutateRemove(collection, "42");

        // NOTE: marked rather than dropped: the row survives until a sync has
        // told the server, or the delete would happen only here.
        assertEquals("1", scalar("SELECT deleted FROM items WHERE link_id = ?", "42"));
        assertTrue("and it leaves the list at once", MailFixtures.merged(store, 10).isEmpty());
    }

    @Test
    public void anOutgoingMessageIsQueuedRatherThanStaged() {
        String source = "From: jane@example.com\r\nSubject: Hi\r\n\r\nthe body\r\n";
        String date = "Mon, 5 Jan 2026 09:00:00 +0000";

        store.outbox().queueSubmission(
                Submission.of(EMAIL, "queued@example.com", "Hi", date),
                source.getBytes(StandardCharsets.UTF_8));

        // NOTE: a message on its way out is an action the engine never sees, so
        // nothing it stages is kept out of a reconcile or held back from a
        // roster replace.
        assertEquals(
                "0",
                scalar("SELECT count(*) FROM items WHERE collection = ?", store.outbox().outboxOf(EMAIL)));

        List<Outbox.Outgoing> waiting = store.outbox().outgoing(EMAIL);
        assertEquals(1, waiting.size());
        assertEquals(source, new String(waiting.get(0).source, StandardCharsets.UTF_8));

        MailStore.StoredMessage row = MailFixtures.merged(store, 10).get(0);
        assertTrue("it says of itself that it has not gone yet", row.pending());
        assertEquals("Hi", row.subject);
        assertEquals(store.outbox().outboxOf(EMAIL), row.collection);
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
     * A mailbox listed in pages lands each page as it comes.
     *
     * <p>A pass cut off between two pages resumes from the cursor the last
     * landed page left, not from the top (pimdir SYNC section 5).
     */
    @Test
    public void aRoundLandsPageByPageAndResumesWhereItStopped() throws Exception {
        String since = "2026-01-01T00:00:00Z";
        List<MailRequest> asked = new ArrayList<>();
        boolean[] cut = {true};
        MailClient server =
                new MailClient() {
                    @Override
                    public MailPage enumerateMailbox(
                            MailSession session, String mailbox, MailRequest request) {
                        asked.add(request);
                        try {
                            MailRequest.Listing listing = request.listing();
                            if (!listing.round()) {
                                return MailWire.page(new JSONObject()
                                        .put("items", new JSONArray())
                                        .put("vanished", new JSONArray())
                                        .put("complete", false)
                                        .put("checkpoint", "cp-2"));
                            }
                            if (listing.cursor() == null) {
                                return MailWire.page(new JSONObject()
                                        .put(
                                                "items",
                                                new JSONArray()
                                                        .put(named("101", "2026-10-07T08:00:00Z"))
                                                        .put(named("100", "2026-10-06T08:00:00Z")))
                                        .put("vanished", new JSONArray())
                                        .put("complete", true)
                                        .put("last", false)
                                        .put("cursor", "7:100")
                                        .put("checkpoint", "cp-1"));
                            }
                            if (cut[0]) {
                                cut[0] = false;
                                throw new IllegalStateException("the connection dropped");
                            }
                            return MailWire.page(new JSONObject()
                                    .put(
                                            "items",
                                            new JSONArray()
                                                    .put(named("99", "2026-02-01T08:00:00Z")))
                                    .put("vanished", new JSONArray())
                                    .put("complete", true)
                                    .put("last", true));
                        } catch (JSONException failure) {
                            throw new AssertionError(failure);
                        }
                    }
                };
        MailEngine remote =
                new MailEngine(
                        pimdir,
                        new Clients(
                                new AccountClient(), new ContactClient(), new CalendarClient(),
                                server, new OfflineClient()),
                        null,
                        accountId);

        try {
            new OfflineClient().syncImmutable(remote, collection, since, false);
            throw new AssertionError("the pass reports the cut");
        } catch (org.pimalaya.client.PimalayaException cut2) {
            assertTrue(cut2.getMessage().contains("dropped"));
        }
        assertEquals(
                "the first page landed before the cut", "Subject 101",
                scalar("SELECT subject FROM mail_summary WHERE link_id = ?", "101"));
        assertEquals("2026-01-01T00:00:00Z", asked.get(0).scope().since());
        assertTrue("a first pass is filling in", store.coverage(collection).filling);
        assertNull("and covers nothing yet", store.coverage(collection).at);

        new OfflineClient().syncImmutable(remote, collection, since, false);
        MailRequest.Listing resumed = asked.get(asked.size() - 1).listing();
        assertEquals("resumed, not restarted", "7:100", resumed.cursor());
        assertEquals("1", scalar("SELECT count(*) FROM items WHERE link_id = '99'"));
        // NOTE: the message the setup filed was in scope and listed by no
        // page of the round, so its close retires it.
        assertEquals("1", scalar("SELECT deleted FROM items WHERE link_id = '42'"));

        new OfflineClient().syncImmutable(remote, collection, since, false);
        MailRequest.Listing delta = asked.get(asked.size() - 1).listing();
        assertEquals("a closed round leaves a delta", "delta", delta.kind());
        assertEquals("the checkpoint taken up front", "cp-1", delta.checkpoint());
        MailStore.Coverage coverage = store.coverage(collection);
        assertEquals("mail since the bound", since, coverage.since);
        assertTrue("and complete", coverage.at != null && !coverage.filling);
    }
}
