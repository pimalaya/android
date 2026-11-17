package org.pimalaya.setup;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.graphics.Rect;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ListAdapter;
import android.widget.ListView;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.pimalaya.FakeAndroidKeyStore;
import org.pimalaya.R;
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
import org.pimalaya.sync.SyncCoordinator;
import org.pimalaya.sync.SyncPasses;
import org.robolectric.Robolectric;
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

    @Test
    public void settingsKeepTheirPositionOnlyWhenReturningFromSignInAgain() {
        String other = "other@example.org";
        store.connect(other, PimDomain.MAIL, IMAP, SMTP, AccountCredential.password(other, "other"));
        AccountHost host = Robolectric.buildActivity(AccountHost.class).setup().get();
        host.setTheme(R.style.AppTheme);
        host.setContentView(R.layout.activity_main);
        AccountSettings settings =
                new AccountSettings(
                        host,
                        parts,
                        null,
                        new ArrayList<>(store.loadAll()),
                        new SyncCoordinator(host, parts, host),
                        Runnable::run,
                        host);
        settings.bind();
        settings.signInAgain = email -> settings.leave();
        settings.open(EMAIL);

        ListView list = host.findViewById(R.id.account_list);
        layoutSettings(list);
        assertEquals(0, list.getFirstVisiblePosition());
        assertFalse(settings.header().scrolled());
        ViewGroup identity = (ViewGroup) settings.header().view;
        View avatar = identity.findViewById(R.id.account_avatar);
        View name = identity.findViewById(R.id.header_title);
        View status = identity.findViewById(R.id.account_pill);
        Rect avatarBounds = new Rect();
        Rect nameBounds = new Rect();
        Rect statusBounds = new Rect();
        avatar.getDrawingRect(avatarBounds);
        name.getDrawingRect(nameBounds);
        status.getDrawingRect(statusBounds);
        identity.offsetDescendantRectToMyCoords(avatar, avatarBounds);
        identity.offsetDescendantRectToMyCoords(name, nameBounds);
        identity.offsetDescendantRectToMyCoords(status, statusBounds);
        assertTrue("avatar stays left of the address", avatarBounds.right < nameBounds.left);
        assertTrue("avatar and address share a row", nameBounds.top < avatarBounds.bottom);
        assertTrue("sync status stays below the identity row", statusBounds.top > avatarBounds.bottom);
        assertNull(host.findViewById(R.id.account_bar).findViewById(R.id.account_avatar));

        list.scrollListBy(nameBounds.bottom - 1);
        layoutSettings(list);
        assertEquals(0, list.getFirstVisiblePosition());
        assertFalse("address is still visible", settings.header().scrolled());
        assertFalse(host.titleShown);
        list.scrollListBy(1);
        layoutSettings(list);
        assertTrue("address has left view", settings.header().scrolled());
        assertTrue(host.titleShown);

        scrollSettingsToEnd(list);
        int position = list.getFirstVisiblePosition();
        int offset = list.getChildAt(0).getTop();
        ListAdapter adapter = list.getAdapter();
        assertTrue("settings are scrolled", position > 0);
        assertTrue(settings.header().scrolled());
        assertTrue(host.titleShown);

        ArrayList<View> actions = new ArrayList<>();
        list.findViewsWithText(
                actions, host.getString(R.string.sign_in_again), View.FIND_VIEWS_WITH_TEXT);
        assertEquals(1, actions.size());
        View action = actions.get(0);
        while (!action.isClickable()) {
            action = (View) action.getParent();
        }
        action.performClick();
        settings.resume(EMAIL);
        layoutSettings(list);

        assertSame(adapter, list.getAdapter());
        assertEquals(position, list.getFirstVisiblePosition());
        assertEquals(offset, list.getChildAt(0).getTop());
        assertTrue(settings.header().scrolled());
        assertEquals(1f, host.findViewById(R.id.account_title).getAlpha(), 0f);
        assertEquals(
                "old",
                AccountEntry.find(store.loadAll(), EMAIL).credential(PimDomain.MAIL).secret);

        settings.leave();
        settings.open(EMAIL);
        layoutSettings(list);
        assertEquals(0, list.getFirstVisiblePosition());
        assertFalse(settings.header().scrolled());
        assertEquals(0f, host.findViewById(R.id.account_title).getAlpha(), 0f);

        scrollSettingsToEnd(list);
        assertTrue(list.getFirstVisiblePosition() > 0);
        settings.leave();
        settings.resume(other);
        layoutSettings(list);
        assertEquals(0, list.getFirstVisiblePosition());
        assertFalse(settings.header().scrolled());
        assertEquals(
                other,
                ((android.widget.TextView) settings.header().view.findViewById(R.id.header_title))
                        .getText().toString());
    }

    private static void scrollSettingsToEnd(ListView list) {
        while (list.canScrollList(1)) {
            int position = list.getFirstVisiblePosition();
            int top = list.getChildAt(0).getTop();
            list.scrollListBy(list.getHeight());
            layoutSettings(list);
            assertTrue(
                    "scroll makes progress",
                    position != list.getFirstVisiblePosition() || top != list.getChildAt(0).getTop());
        }
    }

    private static void layoutSettings(ListView list) {
        list.measure(
                View.MeasureSpec.makeMeasureSpec(360, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(480, View.MeasureSpec.EXACTLY));
        list.layout(0, 0, 360, 480);
    }

    /** Records the shared header's toolbar callback without activity chrome. */
    public static class AccountHost extends WrongGrantTest.FlowHost {
        boolean titleShown;

        @Override
        public void openOverlay(int panel) {
            screen = panel;
        }

        @Override
        public void showBarTitle(boolean shown) {
            titleShown = shown;
            findViewById(R.id.account_title).setAlpha(shown ? 1f : 0f);
        }
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
