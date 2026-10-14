package org.pimalaya.mail;

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
import org.pimalaya.client.AccountClient;
import org.pimalaya.client.CalendarClient;
import org.pimalaya.client.Clients;
import org.pimalaya.client.ContactClient;
import org.pimalaya.client.Flags;
import org.pimalaya.client.FloorReply;
import org.pimalaya.client.MailClient;
import org.pimalaya.client.MailSession;
import org.pimalaya.client.Mailbox;
import org.pimalaya.client.MessageRef;
import org.pimalaya.client.OfflineClient;
import org.pimalaya.pimdir.PimdirAccount;
import org.pimalaya.pimdir.PimdirDb;
import org.pimalaya.pimdir.PimdirSummary;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * A mailbox listed a chunk of messages at a time.
 *
 * <p>A first pass lists the newest {@link MailEngine#FIRST_CHUNK}, leaving the
 * oldest {@code Date} as floor; a widening lists the next chunk below it; the
 * merged list reaches the most recent floor of the mailboxes it shows.
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
     * A server mailbox of {@code size} dated messages, newest on top.
     *
     * <p>UIDs 1 to {@code size}, each {@code spacing} hours older than the one
     * above it.
     */
    private final class Server extends MailClient {
        final int size;
        final int spacing;
        final List<JSONObject> asked = new ArrayList<>();

        /** The account's driver, on this server. */
        final MailEngine engine;

        Server(int size, int spacing) {
            this.size = size;
            this.spacing = spacing;
            engine =
                    new MailEngine(
                            pimdir,
                            new Clients(
                                    new AccountClient(), new ContactClient(),
                                    new CalendarClient(), this, new OfflineClient()),
                            null,
                            new PimdirAccount(context).idOf(EMAIL));
        }

        String dateOf(int uid) {
            return hoursBack((long) (size - uid) * spacing);
        }

        @Override
        public FloorReply floor(MailSession session, String mailbox, String before, int count) {
            List<String> dates = new ArrayList<>();
            for (int uid = size; uid >= 1 && dates.size() < count; uid--) {
                String date = dateOf(uid);
                if (before == null || date.compareTo(before) < 0) {
                    dates.add(date);
                }
            }
            String floor = dates.size() < count ? null : dates.get(dates.size() - 1);
            return new FloorReply(floor, dates.size(), dates);
        }

        @Override
        public JSONObject enumerateMailbox(
                MailSession session, String mailbox, JSONObject request) {
            asked.add(request);
            try {
                JSONObject listing = request.getJSONObject("listing");
                JSONObject scope = request.optJSONObject("scope");
                String since =
                        scope == null || scope.isNull("since") ? null : scope.optString("since");
                String until =
                        scope == null || scope.isNull("until") ? null : scope.optString("until");
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
            } catch (JSONException failure) {
                throw new AssertionError(failure);
            }
        }

        @Override
        public void setFlag(
                MailSession session, MessageRef message, Flags.Marker flag, boolean add) {}
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

        server.engine.sync(inbox);
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

        server.engine.sync(inbox);
        assertEquals(
                "a pass after the first is a delta under the same floor",
                "delta",
                server.asked.get(1).getJSONObject("listing").getString("kind"));

        assertTrue(server.engine.widen(inbox, MailEngine.FIRST_CHUNK));
        JSONObject band = server.asked.get(2);
        assertTrue("a widening lists the band alone", band.getJSONObject("listing").getBoolean("band"));
        assertEquals(server.dateOf(21), band.getJSONObject("scope").getString("since"));
        assertEquals(server.dateOf(71), band.getJSONObject("scope").getString("until"));
        assertEquals(100, stored(inbox));
        assertEquals(server.dateOf(21), edgeOf(inbox).limit);

        // NOTE: twenty left below the floor, fewer than a chunk: the mailbox
        // is whole after this one, and nothing limits it any more.
        assertTrue(server.engine.widen(inbox, MailEngine.FIRST_CHUNK));
        assertEquals(120, stored(inbox));
        assertNull(edgeOf(inbox).limit);
        assertFalse("nothing left to widen", server.engine.widen(inbox, MailEngine.FIRST_CHUNK));
    }

    @Test
    public void theListReachesDownToTheMergedFloor() throws Exception {
        String accountId = new PimdirAccount(context).idOf(EMAIL);
        MailWindow.set(context, accountId, null);
        Server inboxServer = new Server(120, 1);
        Server sentServer = new Server(120, 2);
        inboxServer.engine.sync(inbox);
        sentServer.engine.sync(sent);

        MailStore.Query query = store.query((account, name) -> true, false, false, "");
        String floor = store.floorOf(query, store.edges());
        assertEquals("the inbox's floor is the more recent", inboxServer.dateOf(71), floor);

        // NOTE: the sent mail reaches further back, and its messages below
        // the inbox's floor wait for the inbox to reach them.
        assertEquals(50 + 25, store.count(query.since(floor)));
        assertEquals(100, store.count(query));

        // NOTE: a window more recent than every floor is the list's floor.
        MailWindow.set(context, accountId, inboxServer.dateOf(101));
        assertEquals(inboxServer.dateOf(101), store.floorOf(query, store.edges()));
        // NOTE: a mailbox kept whole brings no window: shown alone, it
        // reaches down to its own floor.
        MailOffline.setWhole(context, sent, true);
        MailStore.Query sentAlone =
                store.query((account, collection) -> collection.equals(sent), false, false, "");
        assertEquals(sentServer.dateOf(71), store.floorOf(sentAlone, store.edges()));
        MailOffline.setWhole(context, sent, false);
        MailWindow.set(context, accountId, null);

        // NOTE: a mailbox the filter hides limits nothing.
        assertEquals(
                inboxServer.dateOf(71),
                store.floorOf(
                        store.query(
                                (account, collection) -> collection.equals(inbox),
                                false,
                                false,
                                ""),
                        store.edges()));
    }
    /**
     * A step from the store widens the mailbox holding the most recent floor.
     *
     * <p>Until a step's worth lies above the frontier, which moves to the
     * {@link MailStep#COUNT}th newest below the old one; the step listing the
     * last of the mailbox says so.
     */
    @Test
    public void aStepFromTheStoreWidensUntilFiftyLieBelowTheFrontier() throws Exception {
        String accountId = new PimdirAccount(context).idOf(EMAIL);
        Server server = new Server(120, 1);
        server.engine.sync(inbox);
        MailWindow.set(context, accountId, server.dateOf(71));

        MailStep.Outcome first =
                new WidenStep().run(server.engine, new StepRun(EMAIL, List.of(inbox), null, false));
        assertEquals(100, stored(inbox));
        assertEquals(server.dateOf(21), first.frontier);
        assertEquals(50, first.brought);
        assertFalse(first.exhausted);
        JSONObject band = server.asked.get(server.asked.size() - 1);
        assertTrue("the band alone", band.getJSONObject("listing").getBoolean("band"));

        MailWindow.moveBack(context, accountId, first.frontier);
        MailStep.Outcome last =
                new WidenStep().run(server.engine, new StepRun(EMAIL, List.of(inbox), null, false));
        assertEquals(120, stored(inbox));
        assertEquals(server.dateOf(1), last.frontier);
        assertTrue("the mailbox is listed whole", last.exhausted);

        // NOTE: what lies at or above a floor counts, all of it with none.
        assertEquals(0, WidenStep.within(List.of("2026-01-02", "2026-01-01"), "2026-01-03"));
        assertEquals(2, WidenStep.within(List.of("2026-01-02", "2026-01-01"), null));
    }

    /**
     * The store's load carries a message's date, never written back.
     *
     * <p>The engine scopes a round by it; a marker staged on a listed message
     * keeps the summary its listing stored.
     */
    @Test
    public void aDatedLoadNeverWritesItsDateBackAsTheSummary() throws Exception {
        Server server = new Server(60, 1);
        server.engine.sync(inbox);

        server.engine.mutateFlags(inbox, "60", Flags.of(Flags.Marker.FLAGGED));
        server.engine.sync(inbox);

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
            names.add(mailbox.name());
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
