package org.pimalaya;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;

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

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * A mailbox listed a chunk of messages at a time: a first pass lists the
 * newest {@link MailEngine#FIRST_CHUNK}, the floor it leaves is the oldest
 * {@code Date} among them, a widening lists the next chunk below it, and the
 * merged list reaches down to the most recent floor of the mailboxes it shows.
 *
 * <p>The server is a list of dated messages answering the engine's listings
 * and the chunk floors the bridge would ask it for, so everything from the
 * engine down to the store is the real thing.
 */
@RunWith(RobolectricTestRunner.class)
public class MailChunksTest {
    private static final String EMAIL = "jane@example.com";
    private static final Instant TOP = Instant.parse("2026-10-01T00:00:00Z");

    private Context context;
    private PimdirDb pimdir;
    private MailStore store;
    private String inbox;
    private String sent;

    @Before
    public void setUp() {
        context = RuntimeEnvironment.getApplication();
        pimdir = new PimdirDb(context);
        store = new MailStore(context, pimdir);
        store.replaceMailboxes(
                EMAIL,
                List.of(new Mailbox("Sent", "sent"), new Mailbox("INBOX", "inbox")));
        inbox = store.collectionOf(EMAIL, "INBOX");
        sent = store.collectionOf(EMAIL, "Sent");
    }

    /** A message's date, {@code hours} before the top of the mailbox. */
    private static String hoursBack(long hours) {
        return TOP.minusSeconds(hours * 3600).toString();
    }

    /**
     * A server mailbox of {@code size} messages, UIDs 1 to {@code size}, the
     * newest at the top and each {@code spacing} hours older than the one
     * above it.
     */
    private final class Server extends MailEngine {
        final int size;
        final int spacing;
        final List<JSONObject> asked = new ArrayList<>();

        Server(int size, int spacing) {
            super(pimdir, new PimalayaClient(), null, new PimdirAccount(context).idOf(EMAIL));
            this.size = size;
            this.spacing = spacing;
        }

        String dateOf(int uid) {
            return hoursBack((long) (size - uid) * spacing);
        }

        @Override
        String floor(String collection, String before, int count) {
            String oldest = null;
            int taken = 0;
            for (int uid = size; uid >= 1 && taken < count; uid--) {
                String date = dateOf(uid);
                if (before == null || date.compareTo(before) < 0) {
                    taken++;
                    oldest = date;
                }
            }
            return taken < count ? null : oldest;
        }

        @Override
        protected JSONObject enumerate(JSONObject yielded) throws JSONException {
            asked.add(yielded);
            JSONObject listing = yielded.getJSONObject("listing");
            JSONObject scope = yielded.optJSONObject("scope");
            String since = scope == null || scope.isNull("since") ? null : scope.optString("since");
            String until = scope == null || scope.isNull("until") ? null : scope.optString("until");
            JSONObject page =
                    new JSONObject().put("vanished", new JSONArray()).put("last", true);
            if ("delta".equals(listing.getString("kind"))) {
                return page.put("items", new JSONArray()).put("complete", false)
                        .put("checkpoint", "cp");
            }
            JSONArray items = new JSONArray();
            for (int uid = size; uid >= 1; uid--) {
                String date = dateOf(uid);
                if ((since == null || date.compareTo(since) >= 0)
                        && (until == null || date.compareTo(until) < 0)) {
                    items.put(named(String.valueOf(uid), date));
                }
            }
            page.put("items", items).put("complete", true);
            if (!listing.optBoolean("band")) {
                page.put("checkpoint", "cp");
            }
            return page;
        }

        @Override
        protected JSONObject push(JSONObject yielded) throws JSONException {
            JSONArray results = new JSONArray();
            JSONArray changes = yielded.getJSONArray("changes");
            for (int index = 0; index < changes.length(); index++) {
                results.put(result(changes.getJSONObject(index).getString("handle"), true, null, null));
            }
            return new JSONObject().put("results", results);
        }
    }

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

    private long stored(String collection) {
        MailStore.Query query = store.query((account, name) -> true, false, false, "");
        long all = 0;
        for (MailStore.StoredMessage message : store.all(query)) {
            if (message.collection.equals(collection)) {
                all++;
            }
        }
        return all;
    }

    private MailStore.Edge edgeOf(String collection) {
        for (MailStore.Edge edge : store.edges()) {
            if (edge.collection.equals(collection)) {
                return edge;
            }
        }
        throw new AssertionError("no edge for " + collection);
    }

    @Test
    public void aFirstPassListsTheNewestChunkAndAWideningTheNextBelowIt() throws Exception {
        Server server = new Server(120, 1);

        server.sync(inbox);
        JSONObject first = server.asked.get(0);
        assertEquals(
                "the floor is the date of the 50th newest message",
                server.dateOf(71),
                first.getJSONObject("scope").getString("since"));
        assertEquals(50, stored(inbox));
        MailStore.Edge edge = edgeOf(inbox);
        assertTrue(edge.listed);
        assertEquals(server.dateOf(71), edge.limit);
        assertEquals("inbox", edge.role);

        server.sync(inbox);
        assertEquals(
                "a pass after the first is a delta under the same floor",
                "delta",
                server.asked.get(1).getJSONObject("listing").getString("kind"));

        assertTrue(server.widen(inbox, MailEngine.FIRST_CHUNK));
        JSONObject band = server.asked.get(2);
        assertTrue("a widening lists the band alone", band.getJSONObject("listing").getBoolean("band"));
        assertEquals(server.dateOf(21), band.getJSONObject("scope").getString("since"));
        assertEquals(server.dateOf(71), band.getJSONObject("scope").getString("until"));
        assertEquals(100, stored(inbox));
        assertEquals(server.dateOf(21), edgeOf(inbox).limit);

        // NOTE: twenty left below the floor, fewer than a chunk: the mailbox
        // is whole after this one, and nothing limits it any more.
        assertTrue(server.widen(inbox, MailEngine.FIRST_CHUNK));
        assertEquals(120, stored(inbox));
        assertNull(edgeOf(inbox).limit);
        assertFalse("nothing left to widen", server.widen(inbox, MailEngine.FIRST_CHUNK));
    }

    @Test
    public void theListReachesDownToTheMostRecentFloorAndWidensTheMailboxHoldingIt()
            throws Exception {
        Server inboxServer = new Server(120, 1);
        Server sentServer = new Server(120, 2);
        inboxServer.sync(inbox);
        sentServer.sync(sent);

        MailStore.Query query = store.query((account, name) -> true, false, false, "");
        String floor = store.floorOf(query);
        assertEquals("the inbox's floor is the more recent", inboxServer.dateOf(71), floor);

        // NOTE: the sent mail reaches further back, and its messages below
        // the inbox's floor wait for the inbox to reach them.
        long shown = store.count(query.reaching(floor));
        assertEquals(50 + 25, shown);
        assertEquals(100, store.count(query));

        List<MailStore.Edge> limiting = MailFill.limiting(store.edges(), query::holds);
        assertEquals(1, limiting.size());
        assertEquals(inbox, limiting.get(0).collection);

        inboxServer.widen(inbox, MailEngine.FIRST_CHUNK);
        limiting = MailFill.limiting(store.edges(), query::holds);
        assertEquals("the sent mail's floor limits the list now", sent, limiting.get(0).collection);
        assertEquals(sentServer.dateOf(71), store.floorOf(query));

        // NOTE: a mailbox the filter hides limits nothing.
        assertEquals(
                inboxServer.dateOf(21),
                store.floorOf(store.query(
                        (account, collection) ->
                                collection.equals(store.collectionOf(EMAIL, "INBOX")),
                        false,
                        false,
                        "")));
    }

    /**
     * The store's load carries a message's date for the engine to scope a
     * round by, and nothing of it is ever written back: a marker staged on a
     * listed message keeps the summary its listing stored.
     */
    @Test
    public void aDatedLoadNeverWritesItsDateBackAsTheSummary() throws Exception {
        Server server = new Server(60, 1);
        server.sync(inbox);

        server.mutateFlags(inbox, "60", new JSONArray().put(MailEngine.FLAGGED));
        server.sync(inbox);

        MailStore.Query query = store.query((account, name) -> true, false, false, "");
        MailStore.StoredMessage newest = store.page(query, null, 0, 1).get(0);
        assertEquals("60", newest.id);
        assertEquals("Subject 60", newest.subject);
        assertEquals("a@example.org", newest.fromAddress);
    }

    @Test
    public void aPassTakesTheInboxFirst() {
        List<Mailbox> ordered =
                MailEngine.ordered(
                        List.of(
                                new Mailbox("Sent Items", "sent"),
                                new Mailbox("Projets", ""),
                                new Mailbox("Projets/Alpha", ""),
                                new Mailbox("Outbox", ""),
                                new Mailbox("Junk Email", "junk"),
                                new Mailbox("Inbox", "inbox"),
                                new Mailbox("Deleted Items", "trash"),
                                new Mailbox("Drafts", "drafts"),
                                new Mailbox("Archive", "")));
        List<String> names = new ArrayList<>();
        for (Mailbox mailbox : ordered) {
            names.add(mailbox.name);
        }
        assertEquals(
                List.of(
                        "Inbox",
                        "Sent Items",
                        "Drafts",
                        "Archive",
                        "Outbox",
                        "Projets",
                        "Projets/Alpha",
                        "Junk Email",
                        "Deleted Items"),
                names);
    }
}
