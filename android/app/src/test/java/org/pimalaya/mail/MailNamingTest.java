package org.pimalaya.mail;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
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
import org.pimalaya.client.OfflineClient;
import org.pimalaya.pimdir.PimdirAccount;
import org.pimalaya.pimdir.PimdirDb;
import org.pimalaya.pimdir.PimdirSummary;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

import java.util.ArrayList;
import java.util.List;

/**
 * How a round names its messages to the store.
 *
 * <p>A Graph delta's bound message keeps its summary, others are read, gone
 * ones left out; a Gmail round names what is held elsewhere or arrived ahead; a
 * round over a covered scope is told apart from a first chunk's band.
 */
@RunWith(RobolectricTestRunner.class)
public class MailNamingTest {
    private static final String EMAIL = "jane@example.com";
    private static final String DATE = "2026-10-01T08:00:00Z";

    private PimdirDb pimdir;
    private MailStore store;
    private String inbox;

    @Before
    public void setUp() {
        Context context = RuntimeEnvironment.getApplication();
        pimdir = new PimdirDb(context);
        store = new MailStore(context, pimdir);
        store.replaceMailboxes(EMAIL, List.of(new Mailbox("Inbox", "inbox")));
        inbox = store.collectionOf(EMAIL, "Inbox");
    }

    /**
     * A mailbox of one message ({@code 1} unless named), ids read from a list.
     */
    private final class Server extends MailClient {
        final List<List<String>> reads = new ArrayList<>();
        final String listed;

        /** The account's driver, on this server. */
        final MailEngine engine;

        Server() {
            this("1");
        }

        Server(String listed) {
            this.listed = listed;
            engine =
                    new MailEngine(
                            pimdir,
                            new Clients(
                                    new AccountClient(), new ContactClient(),
                                    new CalendarClient(), this, new OfflineClient()),
                            null,
                            new PimdirAccount(RuntimeEnvironment.getApplication()).idOf(EMAIL));
        }

        @Override
        public FloorReply floor(MailSession session, String mailbox, String before, int count) {
            return new FloorReply(null, 0, List.of());
        }

        @Override
        public JSONObject enumerateMailbox(
                MailSession session, String mailbox, JSONObject request) {
            try {
                return new JSONObject()
                        .put("items", new JSONArray().put(named(listed)))
                        .put("vanished", new JSONArray())
                        .put("complete", true)
                        .put("last", true)
                        .put("checkpoint", "graph-delta:link");
            } catch (JSONException failure) {
                throw new AssertionError(failure);
            }
        }

        @Override
        public JSONArray nameMessages(MailSession session, String mailbox, List<String> handles) {
            reads.add(handles);
            JSONArray read = new JSONArray();
            for (String handle : handles) {
                if (handle.equals("2")) {
                    read.put(named(handle));
                }
            }
            return read;
        }
    }

    private static JSONObject named(String handle) {
        try {
            return new JSONObject()
                    .put("handle", handle)
                    .put("flags", new JSONArray())
                    .put("linkId", handle)
                    .put(
                            "summary",
                            PimdirSummary.mail(
                                    null, "Subject " + handle, "", "a@example.org", null, DATE, 0,
                                    false))
                    .put("sortKey", PimdirSummary.mailSortKey(DATE));
        } catch (JSONException failure) {
            throw new AssertionError(failure);
        }
    }

    private static JSONObject unnamed(String handle) throws JSONException {
        return new JSONObject()
                .put("handle", handle)
                .put("flags", new JSONArray().put(Flags.Marker.SEEN.wire()))
                .put("linkId", handle);
    }

    @Test
    public void aDeltaMemberIsNamedByTheStoreOrReadById() throws Exception {
        Server server = new Server();
        server.engine.sync(inbox);

        JSONObject page =
                new JSONObject()
                        .put(
                                "items",
                                new JSONArray()
                                        .put(unnamed("1"))
                                        .put(unnamed("2"))
                                        .put(unnamed("3")));
        server.engine.nameUnnamed(inbox, new JSONObject().put("kind", "delta"), page);

        assertEquals(List.of(List.of("2", "3")), server.reads);
        JSONArray items = page.getJSONArray("items");
        assertEquals(2, items.length());

        JSONObject bound = items.getJSONObject(0);
        assertEquals("1", bound.getString("handle"));
        assertFalse("the store keeps its summary", bound.has("summary"));
        assertEquals(Flags.Marker.SEEN.wire(), bound.getJSONArray("flags").getString(0));

        JSONObject read = items.getJSONObject(1);
        assertEquals("2", read.getString("handle"));
        assertTrue(read.has("summary"));
    }

    @Test
    public void aGmailRoundNamesWhatIsHeldElsewhereAndWhatArrivedAhead() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        store.replaceMailboxes(
                EMAIL, List.of(new Mailbox("Inbox", "inbox"), new Mailbox("Clients", "")));
        String clients = store.collectionOf(EMAIL, "Clients");
        MailPolicy.markAccountWide(context, new PimdirAccount(context).idOf(EMAIL), true);
        Server server = new Server();
        server.engine.sync(inbox);
        new Server("5").engine.sync(clients);

        // NOTE: newest received first: 9 arrived after 1, the first held; 5
        // is held in another mailbox; 7 is older mail no step brought.
        JSONObject round =
                new JSONObject().put("kind", "round").put("cursor", JSONObject.NULL);
        JSONObject page =
                new JSONObject()
                        .put("complete", true)
                        .put(
                                "items",
                                new JSONArray()
                                        .put(unnamed("9"))
                                        .put(unnamed("1"))
                                        .put(unnamed("5"))
                                        .put(unnamed("7")));
        server.engine.nameUnnamed(inbox, round, page);
        assertEquals(List.of(List.of("9", "5")), server.reads);
        JSONArray items = page.getJSONArray("items");
        assertEquals(1, items.length());
        assertEquals("1", items.getJSONObject(0).getString("handle"));

        // NOTE: a band round names no arrival.
        server.reads.clear();
        page.put(
                "items",
                new JSONArray().put(unnamed("9")).put(unnamed("5")).put(unnamed("7")));
        server.engine.nameUnnamed(inbox, round.put("band", true), page);
        assertEquals(List.of(List.of("5")), server.reads);
    }

    @Test
    public void aRoundOverACoveredScopeIsToldFromAFirstChunk() throws Exception {
        Server server = new Server();
        JSONObject scope = new JSONObject().put("since", DATE);
        assertFalse("never listed", server.engine.covered(inbox, scope));

        server.engine.sync(inbox);
        assertTrue("listed whole", server.engine.covered(inbox, scope));
        assertTrue(server.engine.covered(inbox, new JSONObject()));
    }
}
