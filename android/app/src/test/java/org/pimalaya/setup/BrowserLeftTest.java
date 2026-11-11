package org.pimalaya.setup;

import static org.junit.Assert.assertEquals;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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

/** The app back on screen while a browser grant is out. */
@RunWith(RobolectricTestRunner.class)
public class BrowserLeftTest {
    private static final String ALICE = "alice@gmail.com";
    private static final String SCOPE = "https://mail.google.com/";

    private LoadingHost host;
    private OauthFlow oauth;
    private OnboardingFlow flow;

    @Before
    public void setUp() throws Exception {
        FakeAndroidKeyStore.install();
        Context context = RuntimeEnvironment.getApplication();
        host = Robolectric.buildActivity(LoadingHost.class).get();
        host.setTheme(R.style.AppTheme);
        host.setContentView(R.layout.activity_main);
        // NOTE: discovery and the redeem run on io; nothing runs there.
        java.lang.reflect.Field io = Host.class.getField("io");
        io.setAccessible(true);
        io.set(host, new Parked());

        PimdirDb pimdir = new PimdirDb(context);
        SecureStore store = new SecureStore(context);
        OauthTokens tokens = new OauthTokens("old", "Bearer", 3600, "old-refresh", SCOPE, 0);
        store.connect(
                ALICE,
                PimDomain.MAIL,
                AccountClient.googleBase(ALICE),
                null,
                AccountCredential.oauth(
                        tokens,
                        new OauthGrant(
                                Oauth.GOOGLE_TOKEN_ENDPOINT, Oauth.GOOGLE_CLIENT_ID, null, SCOPE)));
        SyncPasses.Parts parts =
                new SyncPasses.Parts(
                        pimdir,
                        new CardStore(context, pimdir),
                        store,
                        new MailStore(context, pimdir),
                        new EventStore(context, pimdir),
                        Clients.real());
        List<AccountEntry> accounts = new ArrayList<>(store.loadAll());
        oauth = new OauthFlow(host, new AccountClient(), host);
        flow =
                new OnboardingFlow(
                        host, parts, accounts, new SyncCoordinator(host, parts, host), host, oauth);
        oauth.onBusy = flow::busy;
        oauth.onAborted = flow::grantAborted;
        flow.bind();
        flow.openOn(AccountEntry.find(accounts, ALICE));
    }

    /** The consent page left in the browser: Continue offered again. */
    @Test
    public void backWithNoRedirectStopsTheLoader() {
        host.findViewById(R.id.signin_continue).performClick();
        GoogleNoticeTest.agree();
        assertEquals("the grant is out", Boolean.TRUE, host.loading.get(R.id.signin_continue));

        flow.resumed();

        assertEquals(Boolean.FALSE, host.loading.get(R.id.signin_continue));
    }

    /** A redirect landed before the app shows: the loader stays, redeeming. */
    @Test
    public void backWithTheRedirectKeepsTheLoader() throws Exception {
        host.findViewById(R.id.signin_continue).performClick();
        GoogleNoticeTest.agree();
        String state = new PendingGrantStore(host).load().optString("state");
        oauth.handleRedirect(
                new Intent(
                        Intent.ACTION_VIEW,
                        Uri.parse(Oauth.GOOGLE_REDIRECT_URI + "?code=c0de&state=" + state)));

        flow.resumed();

        assertEquals(Boolean.TRUE, host.loading.get(R.id.signin_continue));
    }

    /** The flow's host, each button's loader remembered. */
    public static class LoadingHost extends WrongGrantTest.FlowHost {
        final Map<Integer, Boolean> loading = new HashMap<>();

        @Override
        public void setAuthLoading(int buttonId, int progressId, boolean on) {
            loading.put(buttonId, on);
        }
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
