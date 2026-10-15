package org.pimalaya.mail;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
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
import org.pimalaya.client.MailPage;
import org.pimalaya.client.MailRequest;
import org.pimalaya.client.MailSession;
import org.pimalaya.client.MailWire;
import org.pimalaya.client.Mailbox;
import org.pimalaya.client.Named;
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
        public MailPage enumerateMailbox(MailSession session, String mailbox, MailRequest request) {
            try {
                return MailWire.page(new JSONObject()
                        .put("items", new JSONArray().put(named(listed)))
                        .put("vanished", new JSONArray())
                        .put("complete", true)
                        .put("last", true)
                        .put("checkpoint", "graph-delta:link"));
            } catch (JSONException failure) {
                throw new AssertionError(failure);
            }
        }

        @Override
        public List<Named> nameMessages(
                MailSession session, String mailbox, List<String> handles) {
            reads.add(handles);
            List<Named> read = new ArrayList<>();
            for (String handle : handles) {
                if (handle.equals("2")) {
                    read.add(MailWire.named(named(handle)));
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

    private static Named unnamed(String handle) {
        return new Named(List.of(Flags.Marker.SEEN.wire()), handle, handle, "", null);
    }

    /** A page of {@code items}, a round's when {@code complete}. */
    private static MailPage page(boolean complete, Named... items) {
        return new MailPage(null, complete, null, false, List.of(items), true, List.of());
    }

    @Test
    public void aDeltaMemberIsNamedByTheStoreOrReadById() throws Exception {
        Server server = new Server();
        server.engine.sync(inbox);

        List<Named> items =
                server.engine.nameUnnamed(
                        inbox,
                        new MailRequest.Listing("delta", "cp", null, false),
                        page(false, unnamed("1"), unnamed("2"), unnamed("3")));

        assertEquals(List.of(List.of("2", "3")), server.reads);
        assertEquals(2, items.size());

        Named bound = items.get(0);
        assertEquals("1", bound.handle());
        assertNull("the store keeps its summary", bound.summary());
        assertEquals(List.of(Flags.Marker.SEEN.wire()), bound.flags());

        Named read = items.get(1);
        assertEquals("2", read.handle());
        assertNotNull(read.summary());
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
        List<Named> items =
                server.engine.nameUnnamed(
                        inbox,
                        new MailRequest.Listing("round", null, null, false),
                        page(true, unnamed("9"), unnamed("1"), unnamed("5"), unnamed("7")));
        assertEquals(List.of(List.of("9", "5")), server.reads);
        assertEquals(1, items.size());
        assertEquals("1", items.get(0).handle());

        // NOTE: a band round names no arrival.
        server.reads.clear();
        server.engine.nameUnnamed(
                inbox,
                new MailRequest.Listing("round", null, null, true),
                page(true, unnamed("9"), unnamed("5"), unnamed("7")));
        assertEquals(List.of(List.of("5")), server.reads);
    }

    @Test
    public void aRoundOverACoveredScopeIsToldFromAFirstChunk() throws Exception {
        Server server = new Server();
        MailRequest.Scope scope = new MailRequest.Scope(DATE, null);
        assertFalse("never listed", server.engine.covered(inbox, scope));

        server.engine.sync(inbox);
        assertTrue("listed whole", server.engine.covered(inbox, scope));
        assertTrue(server.engine.covered(inbox, new MailRequest.Scope(null, null)));
    }
}
