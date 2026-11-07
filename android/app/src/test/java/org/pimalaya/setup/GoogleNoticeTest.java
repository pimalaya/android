package org.pimalaya.setup;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.os.Looper;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.TimeUnit;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.pimalaya.FakeAndroidKeyStore;
import org.pimalaya.R;
import org.pimalaya.account.AccountCredential;
import org.pimalaya.account.AccountEntry;
import org.pimalaya.account.Oauth;
import org.pimalaya.account.PimDomain;
import org.pimalaya.account.SecureStore;
import org.pimalaya.calendar.EventStore;
import org.pimalaya.client.AccountClient;
import org.pimalaya.client.Clients;
import org.pimalaya.client.OauthGrant;
import org.pimalaya.client.OauthTokens;
import org.pimalaya.contact.CardStore;
import org.pimalaya.mail.MailStore;
import org.pimalaya.pimdir.PimdirDb;
import org.pimalaya.sync.SyncCoordinator;
import org.pimalaya.sync.SyncPasses;
import org.pimalaya.ui.Host;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.shadows.ShadowAlertDialog;

/**
 * The notice of what a Google grant reads, agreed to before Google's consent
 * page opens.
 */
@RunWith(RobolectricTestRunner.class)
public class GoogleNoticeTest {
    private static final String ALICE = "alice@gmail.com";

    private BrowserLeftTest.LoadingHost host;

    @Before
    public void setUp() throws Exception {
        FakeAndroidKeyStore.install();
        Context context = RuntimeEnvironment.getApplication();
        host = Robolectric.buildActivity(BrowserLeftTest.LoadingHost.class).get();
        host.setTheme(R.style.AppTheme);
        host.setContentView(R.layout.activity_main);
        // NOTE: discovery and the redeem run on io; nothing runs there.
        java.lang.reflect.Field io = Host.class.getField("io");
        io.setAccessible(true);
        io.set(host, new Parked());

        String scope = Oauth.GOOGLE_MAIL_SCOPE;
        PimdirDb pimdir = new PimdirDb(context);
        SecureStore store = new SecureStore(context);
        store.connect(
                ALICE,
                PimDomain.MAIL,
                "imaps://imap.gmail.com:993",
                "smtps://smtp.gmail.com:465",
                AccountCredential.oauth(
                        new OauthTokens("old", "Bearer", 3600, "old-refresh", scope, 0),
                        new OauthGrant(
                                Oauth.GOOGLE_TOKEN_ENDPOINT, Oauth.GOOGLE_CLIENT_ID, null, scope)));
        SyncPasses.Parts parts =
                new SyncPasses.Parts(
                        pimdir,
                        new CardStore(context, pimdir),
                        store,
                        new MailStore(context, pimdir),
                        new EventStore(context, pimdir),
                        Clients.real());
        List<AccountEntry> accounts = new ArrayList<>(store.loadAll());
        OauthFlow oauth = new OauthFlow(host, new AccountClient(), host);
        OnboardingFlow flow =
                new OnboardingFlow(
                        host, parts, accounts, new SyncCoordinator(host, parts, host), host, oauth);
        oauth.onBusy = flow::busy;
        oauth.onAborted = flow::grantAborted;
        flow.bind();
        flow.openOn(AccountEntry.find(accounts, ALICE));
    }

    /** Taps Agree on the notice showing. */
    static void agree() {
        AlertDialog notice = ShadowAlertDialog.getLatestAlertDialog();
        notice.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        shadowOf(Looper.getMainLooper()).idle();
    }

    @Test
    public void continueShowsTheNoticeAndOpensNothingUntilAgreed() {
        host.findViewById(R.id.signin_continue).performClick();

        AlertDialog notice = ShadowAlertDialog.getLatestAlertDialog();
        assertNotNull("the notice", notice);
        assertTrue(notice.isShowing());
        assertNull("no browser yet", shadowOf(host).getNextStartedActivity());
        assertFalse(
                "no loader yet",
                Boolean.TRUE.equals(host.loading.get(R.id.signin_continue)));

        agree();

        Intent browser = shadowOf(host).getNextStartedActivity();
        assertNotNull("the consent page", browser);
        assertEquals("accounts.google.com", browser.getData().getHost());
        assertEquals(Boolean.TRUE, host.loading.get(R.id.signin_continue));
    }

    /** Back or a tap outside declines: nothing opens, Continue asks again. */
    @Test
    public void backingOutDeclinesAndContinueShowsItAgain() {
        host.findViewById(R.id.signin_continue).performClick();
        AlertDialog first = ShadowAlertDialog.getLatestAlertDialog();

        first.cancel();
        shadowOf(Looper.getMainLooper()).idle();

        assertNull("no browser", shadowOf(host).getNextStartedActivity());
        assertEquals(Boolean.FALSE, host.loading.get(R.id.signin_continue));

        host.findViewById(R.id.signin_continue).performClick();
        AlertDialog again = ShadowAlertDialog.getLatestAlertDialog();
        assertNotSame(first, again);
        assertTrue(again.isShowing());
    }

    /** The notice names what the grant asks for, and nothing else. */
    @Test
    public void theNoticeNamesOnlyTheDataAsked() {
        Context context = RuntimeEnvironment.getApplication();
        String mail = GoogleNotice.text(context, Set.of(Oauth.GOOGLE_MAIL_SCOPE)).toString();
        assertTrue(mail.contains(context.getString(R.string.google_notice_mail)));
        assertTrue(mail.contains(context.getString(R.string.google_notice_identity)));
        assertTrue(mail.contains(context.getString(R.string.google_notice_use)));
        assertFalse(mail.contains(context.getString(R.string.google_notice_calendar)));
        assertFalse(mail.contains(context.getString(R.string.google_notice_mirror)));

        String contacts =
                GoogleNotice.text(context, Set.of(Oauth.GOOGLE_PEOPLE_SCOPE)).toString();
        assertTrue(contacts.contains(context.getString(R.string.google_notice_contacts)));
        assertTrue(contacts.contains(context.getString(R.string.google_notice_mirror)));
        assertFalse(
                "no address read for contacts alone",
                contacts.contains(context.getString(R.string.google_notice_identity)));
        assertFalse(contacts.contains(context.getString(R.string.google_notice_mail)));
    }

    /** An executor holding its tasks, so nothing reaches the network. */
    private static final class Parked extends AbstractExecutorService {
        @Override
        public void execute(Runnable task) {}

        @Override
        public void shutdown() {}

        @Override
        public List<Runnable> shutdownNow() {
            return new ArrayList<>();
        }

        @Override
        public boolean isShutdown() {
            return false;
        }

        @Override
        public boolean isTerminated() {
            return false;
        }

        @Override
        public boolean awaitTermination(long timeout, TimeUnit unit) {
            return true;
        }
    }
}
