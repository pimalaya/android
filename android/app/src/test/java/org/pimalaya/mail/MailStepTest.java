package org.pimalaya.mail;

import static org.junit.Assert.assertArrayEquals;
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
import org.pimalaya.R;
import org.pimalaya.client.Account;
import org.pimalaya.client.AccountClient;
import org.pimalaya.client.CalendarClient;
import org.pimalaya.client.Clients;
import org.pimalaya.client.ContactClient;
import org.pimalaya.client.Flags;
import org.pimalaya.client.MailClient;
import org.pimalaya.client.MailSession;
import org.pimalaya.client.Mailbox;
import org.pimalaya.client.MessageSources;
import org.pimalaya.client.NamedStep;
import org.pimalaya.client.NamedStepItem;
import org.pimalaya.client.OfflineClient;
import org.pimalaya.client.OfflineDriver;
import org.pimalaya.client.StepRequest;
import org.pimalaya.pimdir.PimdirAccount;
import org.pimalaya.pimdir.PimdirCollections;
import org.pimalaya.pimdir.PimdirDb;
import org.pimalaya.pimdir.PimdirSummary;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * A Gmail step from the engine down to the store: the messages the bridge
 * read whole land in every mailbox their labels name, the first step
 * opening every mailbox (the trash included) as a round closed on the
 * history id, a later one adding to them as a delta; each body is filed
 * from its file once and linked into the other mailboxes. And the footer's
 * line under "Load more".
 */
@RunWith(RobolectricTestRunner.class)
public class MailStepTest {
    private static final String EMAIL = "jane@gmail.com";

    private Context context;
    private PimdirDb pimdir;
    private MailStore store;
    private String accountId;
    private String inbox;
    private String clients;
    private String trash;

    @Before
    public void setUp() {
        context = RuntimeEnvironment.getApplication();
        pimdir = new PimdirDb(context);
        store = new MailStore(context, pimdir);
        store.replaceMailboxes(
                EMAIL,
                List.of(
                        new Mailbox("INBOX", "inbox"),
                        new Mailbox("Clients", ""),
                        new Mailbox("TRASH", "trash")));
        accountId = new PimdirAccount(context).idOf(EMAIL);
        MailPolicy.markAccountWide(context, accountId, true);
        inbox = store.collectionOf(EMAIL, "INBOX");
        clients = store.collectionOf(EMAIL, "Clients");
        trash = store.collectionOf(EMAIL, "TRASH");
    }

    /** One message the fake account serves: its id, date, reception and mailboxes. */
    private static final class Mail {
        final String id;
        final String date;
        final Instant received;
        final List<String> mailboxes;

        Mail(String id, String date, Instant received, String... mailboxes) {
            this.id = id;
            this.date = date;
            this.received = received;
            this.mailboxes = List.of(mailboxes);
        }

        byte[] source() {
            return ("Date: " + date + "\r\nSubject: Mail " + id + "\r\n\r\nBody " + id + "\r\n")
                    .getBytes(StandardCharsets.UTF_8);
        }
    }

    /** A Gmail account whose next step serves {@code next}. */
    private final class Server extends MailClient {
        List<Mail> next = List.of();
        List<String> anchors;
        final List<Set<String>> held = new ArrayList<>();
        final List<String> listing = new ArrayList<>();
        final List<StepRequest> asked = new ArrayList<>();

        /** The messages whose file the bridge fails to write. */
        Set<String> missing = Set.of();

        /** The account's driver, on this server. */
        final MailEngine engine =
                new MailEngine(
                        pimdir,
                        new Clients(
                                new AccountClient(), new ContactClient(), new CalendarClient(),
                                this, new OfflineClient()),
                        null,
                        accountId);

        @Override
        public NamedStep step(
                MailSession session, StepRequest request, File dir, OfflineDriver store) {
            try {
                // NOTE: the bridge asks which of what it lists the store
                // holds; this account lists every message it served.
                for (Mail mail : next) {
                    if (!listing.contains(mail.id)) {
                        listing.add(mail.id);
                    }
                }
                this.held.add(held(store, listing));
                asked.add(request);
                List<NamedStepItem> items = new ArrayList<>();
                for (Mail mail : next) {
                    File file = new File(dir, mail.id + ".eml");
                    if (!missing.contains(mail.id)) {
                        Files.write(file.toPath(), mail.source());
                    }
                    items.add(
                            new NamedStepItem(
                                    List.of(Flags.Marker.SEEN.wire()),
                                    mail.id,
                                    mail.id,
                                    mail.mailboxes,
                                    file.getAbsolutePath(),
                                    mail.received.toEpochMilli(),
                                    mail.source().length,
                                    PimdirSummary.mailSortKey(mail.date),
                                    PimdirSummary.mail(
                                            null, "Mail " + mail.id, "", "a@example.org",
                                            null, mail.date, 0, false)));
                }
                return new NamedStep(anchors, false, false, "h1", items);
            } catch (IOException failure) {
                throw new AssertionError(failure);
            }
        }

        /** Which of a listed page the store holds, asked as the bridge asks it. */
        private Set<String> held(OfflineDriver store, List<String> listing) {
            try {
                String asked =
                        new JSONObject()
                                .put("op", "held")
                                .put("handles", new JSONArray(listing))
                                .toString();
                JSONArray held = new JSONObject(store.serve(asked)).getJSONArray("held");
                Set<String> handles = new HashSet<>();
                for (int index = 0; index < held.length(); index++) {
                    handles.add(held.getString(index));
                }
                return handles;
            } catch (JSONException failure) {
                throw new AssertionError(failure);
            }
        }

        @Override
        public MessageSources fetchSources(
                MailSession session, String mailbox, List<String> ids, File dir) {
            return new MessageSources(List.of(), List.of());
        }
    }

    private List<String> mailboxes() {
        List<String> collections = new ArrayList<>();
        for (Mailbox mailbox : MailEngine.ordered(
                List.of(
                        new Mailbox("INBOX", "inbox"),
                        new Mailbox("Clients", ""),
                        new Mailbox("TRASH", "trash")))) {
            collections.add(store.collectionOf(EMAIL, mailbox.name()));
        }
        return collections;
    }

    private long held(String collection) {
        long count = 0;
        MailStore.Query all = store.query((account, name) -> true, false, false, "");
        for (MailStore.StoredMessage message : store.all(all)) {
            if (message.collection.equals(collection)) {
                count++;
            }
        }
        return count;
    }

    @Test
    public void aStepLandsWholeMessagesInEveryLabelAndMovesTheFrontier() throws Exception {
        Instant now = Instant.parse("2026-10-09T12:00:00Z");
        Server server = new Server();
        Mail labelled = new Mail("m1", "2026-10-09T11:00:00Z", now, "INBOX", "Clients");
        Mail older = new Mail("m2", "2026-10-08T20:00:00Z", now, "INBOX");
        Mail imported = new Mail("m3", "2019-03-01T08:00:00Z", now, "INBOX");
        server.next = List.of(labelled, older, imported);

        MailStep.Outcome first =
                new GmailStep().run(server.engine, new StepRun(EMAIL, mailboxes(), null, true));

        assertEquals(3, held(inbox));
        assertEquals(1, held(clients));
        assertEquals(0, held(trash));
        assertArrayEquals(labelled.source(), store.storedSource(inbox, "m1"));
        assertArrayEquals(
                "linked, not read again", labelled.source(), store.storedSource(clients, "m1"));
        assertArrayEquals(imported.source(), store.storedSource(inbox, "m3"));
        for (String collection : List.of(inbox, clients, trash)) {
            assertNotNull(collection + " opened", store.coverage(collection).at);
        }
        assertEquals("the import is held, not the frontier", older.date, first.frontier);
        assertEquals(3, first.brought);
        assertFalse(first.exhausted);
        assertEquals(Long.valueOf(now.toEpochMilli()), MailWindow.reach(context, accountId));

        // NOTE: the next step skips what is held, and adds to the inbox.
        Mail next = new Mail("m4", "2026-10-07T09:00:00Z", now.minusSeconds(172_800), "INBOX");
        server.next = List.of(next);
        MailStep.Outcome second =
                new GmailStep().run(server.engine, new StepRun(EMAIL, mailboxes(), null, false));
        assertEquals(Set.of("m1", "m2", "m3"), server.held.get(1));
        assertEquals(4, held(inbox));
        assertArrayEquals(next.source(), store.storedSource(inbox, "m4"));
        assertEquals(next.date, second.frontier);
        assertEquals(
                Long.valueOf(next.received.toEpochMilli()), MailWindow.reach(context, accountId));
    }


    @Test
    public void anArchivedMessageIsHeldInAllMailAndNotReadAgain() throws Exception {
        Instant now = Instant.parse("2026-10-09T12:00:00Z");
        Server server = new Server();
        store.ensureAllMail(EMAIL);
        String allMail = MailStore.allMailOf(accountId);
        List<String> collections = new ArrayList<>(mailboxes());
        collections.add(allMail);
        Mail archived = new Mail("a1", "2026-10-09T10:00:00Z", now);
        Mail inboxed = new Mail("m1", "2026-10-09T11:00:00Z", now, "INBOX");
        server.next = List.of(inboxed, archived);

        new GmailStep().run(server.engine, new StepRun(EMAIL, collections, null, true));

        assertEquals(1, held(inbox));
        assertNull("All Mail holds no body", store.storedSource(allMail, "a1"));
        for (PimdirCollections.Stored shown : new PimdirCollections(pimdir, context)
                .list(PimdirSummary.MAIL)) {
            assertFalse("All Mail is never listed", shown.id.equals(allMail));
        }

        // NOTE: the next step finds the archived message held.
        server.next = List.of();
        new GmailStep().run(server.engine, new StepRun(EMAIL, collections, null, false));
        assertEquals(Set.of("m1", "a1"), server.held.get(1));
    }
    @Test
    public void aStepsBodyThatDidNotLandIsOwed() throws Exception {
        Instant now = Instant.parse("2026-10-09T12:00:00Z");
        Server server = new Server();
        server.missing = Set.of("m2");
        server.next =
                List.of(
                        new Mail("m1", "2026-10-09T11:00:00Z", now, "INBOX"),
                        new Mail("m2", "2019-03-01T08:00:00Z", now, "INBOX", "Clients"));

        new GmailStep().run(server.engine, new StepRun(EMAIL, mailboxes(), null, true));

        assertEquals("listed all the same", 2, held(inbox));
        assertNull(store.storedSource(inbox, "m2"));
        assertTrue("owed whatever its date", MailOwed.owed(context, inbox, "m2"));
        assertTrue(MailOwed.owed(context, clients, "m2"));
        assertFalse(MailOwed.owed(context, inbox, "m1"));
    }

    @Test
    public void aJmapAccountStepsThroughTheAccountAndKeepsItsAnchors() throws Exception {
        assertTrue(
                MailStep.of(new Account("jmap://api.fastmail.com/jmap/session", "", "t", null))
                        instanceof GmailStep);
        assertTrue(
                MailStep.of(new Account("imaps://imap.example.org", "", "p", null))
                        instanceof ImapStep);

        Instant now = Instant.parse("2026-10-09T12:00:00Z");
        Server server = new Server();
        server.next =
                List.of(
                        new Mail("m1", "2026-10-09T11:00:00Z", now, "INBOX", "Clients"),
                        new Mail("m2", "2026-10-09T10:00:00Z", now, "INBOX"));
        server.anchors = List.of("m2", "m1");
        new GmailStep().run(server.engine, new StepRun(EMAIL, mailboxes(), null, true));
        assertEquals(List.of("m2", "m1"), MailWindow.anchors(context, accountId, null));

        server.next = List.of(new Mail("c1", "2026-10-08T10:00:00Z", now, "Clients"));
        server.anchors = List.of("c1");
        new GmailStep().run(server.engine, new StepRun(EMAIL, mailboxes(), clients, false));
        assertEquals(
                "a view with none starts after the account's",
                List.of("m2", "m1"),
                server.asked.get(1).anchors());
        assertEquals(List.of("c1"), MailWindow.anchors(context, accountId, "Clients"));
        assertEquals(
                "a view's step leaves the account's anchors",
                List.of("m2", "m1"),
                MailWindow.anchors(context, accountId, null));
        assertEquals(2, held(clients));
    }

    @Test
    public void theFooterSaysWhatAStepBringsAndThatItWaitsForWifi() {
        assertEquals("About 50 more per account", MailList.info(context, true, false));
        assertEquals(
                "About 50 more per account · Waits for Wi-Fi",
                MailList.info(context, false, false));
        assertEquals("About 50 more per mailbox", MailList.info(context, true, true));
    }

    @Test
    public void aStepCountsItsBodiesOutOfAStepThenWhatItBrought() throws Exception {
        Instant now = Instant.parse("2026-10-09T12:00:00Z");
        Server server = new Server();
        server.next =
                List.of(
                        new Mail("m1", "2026-10-09T11:00:00Z", now, "INBOX"),
                        new Mail("m2", "2026-10-09T10:00:00Z", now, "INBOX", "Clients"));
        List<int[]> told = Collections.synchronizedList(new ArrayList<>());
        server.engine.tally =
                new MailTally((landed, planned) -> told.add(new int[] {landed, planned}));

        new GmailStep().run(server.engine, new StepRun(EMAIL, mailboxes(), null, true));

        assertArrayEquals("planned a step before reading", new int[] {0, 50}, told.get(0));
        assertArrayEquals(
                "closed on the two files it wrote", new int[] {2, 2}, told.get(told.size() - 1));
    }

    @Test
    public void theFooterSaysItIsLoadingWhileTheSyncIsHeld() {
        assertEquals(MailList.FooterState.LOAD, MailList.footerState(true, false));
        assertEquals(MailList.FooterState.LOADING, MailList.footerState(true, true));
        assertEquals(MailList.FooterState.WIFI, MailList.footerState(false, false));
        assertEquals(
                "no step runs off Wi-Fi",
                MailList.FooterState.WIFI,
                MailList.footerState(false, true));
        assertEquals("Loading more", context.getString(R.string.mail_more_loading));
    }

    @Test
    @Config(qualifiers = "fr")
    public void inFrenchTheFooterSaysItIsLoading() {
        assertEquals("Chargement en cours", context.getString(R.string.mail_more_loading));
    }
}
