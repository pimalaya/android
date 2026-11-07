package org.pimalaya.setup;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import java.util.List;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.pimalaya.FakeAndroidKeyStore;
import org.pimalaya.account.AccountCredential;
import org.pimalaya.account.AccountEntry;
import org.pimalaya.account.PimDomain;
import org.pimalaya.account.SecureStore;
import org.pimalaya.calendar.EventStore;
import org.pimalaya.client.Calendar;
import org.pimalaya.client.Clients;
import org.pimalaya.client.ComposeDraft;
import org.pimalaya.client.Flags;
import org.pimalaya.client.MailClient;
import org.pimalaya.client.Mailbox;
import org.pimalaya.contact.CardStore;
import org.pimalaya.mail.MailStager;
import org.pimalaya.mail.MailStore;
import org.pimalaya.mail.MailSubmit;
import org.pimalaya.pimdir.PimdirCollections;
import org.pimalaya.pimdir.PimdirDb;
import org.pimalaya.pimdir.PimdirSummary;
import org.pimalaya.sync.FirstSync;
import org.pimalaya.sync.SyncPasses;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

/** Signing an account in again: what it keeps, and what a move drops. */
@RunWith(RobolectricTestRunner.class)
public class SignInAgainTest {
    private static final String EMAIL = "jane@example.org";
    private static final String IMAP = "imaps://imap.example.org:993";
    private static final String SMTP = "smtps://smtp.example.org:465";
    private static final String DAV = "https://dav.example.org/";

    private Context context;
    private PimdirDb pimdir;
    private SecureStore store;
    private MailStore mail;
    private EventStore events;
    private MailStager stager;
    private SyncPasses.Parts parts;
    private String inbox;

    @Before
    public void setUp() throws Exception {
        FakeAndroidKeyStore.install();
        context = RuntimeEnvironment.getApplication();
        pimdir = new PimdirDb(context);
        store = new SecureStore(context);
        mail = new MailStore(context, pimdir);
        events = new EventStore(context, pimdir);
        stager = new MailStager(pimdir, Clients.real());
        parts =
                new SyncPasses.Parts(
                        pimdir,
                        new CardStore(context, pimdir),
                        store,
                        mail,
                        events,
                        Clients.real());

        AccountCredential password = AccountCredential.password(EMAIL, "old");
        store.connect(EMAIL, PimDomain.MAIL, IMAP, SMTP, password);
        store.connect(EMAIL, PimDomain.CALENDAR, DAV, null, password);

        mail.replaceMailboxes(
                EMAIL, List.of(new Mailbox("INBOX", "inbox"), new Mailbox("Sent", "sent")));
        inbox = mail.collectionOf(EMAIL, "INBOX");
        events.replaceCalendars(
                EMAIL,
                List.of(new Calendar("work", "Work", DAV + "cal/work/", null, null, "", true)));

        // NOTE: a message the server agreed on, marked read here since.
        JSONObject placement =
                new JSONObject()
                        .put("collection", inbox)
                        .put("handle", "42")
                        .put("linkId", "42")
                        .put("level", "meta")
                        .put("status", "clean")
                        .put("flags", new JSONArray())
                        .put("base", new JSONObject().put("flags", new JSONArray()));
        JSONObject write =
                new JSONObject()
                        .put("op", "write")
                        .put(
                                "writes",
                                new JSONArray()
                                        .put(
                                                new JSONObject()
                                                        .put("op", "upsert")
                                                        .put("placement", placement)));
        assertEquals("{}", stager.serve(write.toString()));
        stager.mutateFlags(inbox, "42", Flags.of(Flags.Marker.SEEN));

        new MailSubmit(new MailClient(), mail, stager)
                .queue(
                        new ComposeDraft(
                                EMAIL,
                                "",
                                "bob@example.org",
                                "",
                                "",
                                "Waiting",
                                "the body\n",
                                false,
                                "Wed, 14 Oct 2026 10:00:00 +0200",
                                "<waiting@example.org>",
                                "",
                                ""));
    }

    /** The sign-in the account would save, its mail at {@code imap}. */
    private AccountEntry signedIn(String imap, String login, String secret) {
        AccountCredential credential = AccountCredential.password(login, secret);
        return AccountCommit.assemble(
                EMAIL,
                List.of(
                        new AccountCommit.Connection(PimDomain.MAIL, imap, SMTP, credential),
                        new AccountCommit.Connection(PimDomain.CALENDAR, DAV, null, credential)));
    }

    private int owing() {
        return new PimdirCollections(pimdir, context)
                .owing(EMAIL, PimdirSummary.MAIL, mail.outbox().outboxOf(EMAIL));
    }

    @Test
    public void aPasswordReplacedKeepsTheOutboxAndStagedEdits() {
        AccountEntry stored = AccountEntry.find(store.loadAll(), EMAIL);
        assertEquals(1, mail.outbox().outgoing(EMAIL).size());
        assertEquals("the read mark and the sent copy", 2, owing());

        AccountCommit.Again saved =
                AccountCommit.again(context, parts, stored, signedIn(IMAP, EMAIL, "new"));

        assertEquals("the outbox kept", 1, mail.outbox().outgoing(EMAIL).size());
        assertEquals("the staged edits kept", 2, owing());
        assertEquals("new", saved.stored().credential(PimDomain.MAIL).secret);
        assertEquals(
                "new",
                AccountEntry.find(store.loadAll(), EMAIL).credential(PimDomain.CALENDAR).secret);
        assertEquals(Set.of(), saved.moved());
        assertFalse(FirstSync.owing(context, PimDomain.MAIL).contains(EMAIL));
    }

    @Test
    public void aServerChangeDropsAndListsThatDomainAlone() {
        AccountEntry stored = AccountEntry.find(store.loadAll(), EMAIL);

        AccountCommit.Again saved =
                AccountCommit.again(
                        context,
                        parts,
                        stored,
                        signedIn("imaps://imap.new.org:993", EMAIL, "new"));

        assertEquals("the old server's changes dropped", 0, owing());
        assertTrue(
                "its mailboxes dropped",
                mail.loadMailboxes(List.of(EMAIL)).stream().noneMatch(box -> box.id.equals(inbox)));
        assertTrue("listed again", FirstSync.owing(context, PimDomain.MAIL).contains(EMAIL));
        assertEquals("the outbox kept", 1, mail.outbox().outgoing(EMAIL).size());
        assertEquals("the calendars kept", 1, events.loadCalendars().size());
        assertFalse(FirstSync.owing(context, PimDomain.CALENDAR).contains(EMAIL));
        assertEquals(Set.of(PimDomain.MAIL), saved.moved());
        assertEquals(
                "imaps://imap.new.org:993",
                AccountEntry.find(store.loadAll(), EMAIL).connection(PimDomain.MAIL).baseUrl);
    }

    @Test
    public void anotherLoginAtTheSameServerMovesTooButADifferentCaseDoesNot() {
        AccountEntry stored = AccountEntry.find(store.loadAll(), EMAIL);

        assertEquals(
                Set.of(PimDomain.MAIL, PimDomain.CALENDAR),
                AccountCommit.moved(stored, signedIn(IMAP, "jane2", "new")));
        assertEquals(
                Set.of(),
                AccountCommit.moved(stored, signedIn(IMAP + "/", "JANE@example.org", "x")));
    }
}
