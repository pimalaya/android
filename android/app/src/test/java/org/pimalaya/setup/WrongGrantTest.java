package org.pimalaya.setup;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
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
import org.pimalaya.phone.PhoneMirror;
import org.pimalaya.pimdir.PimdirDb;
import org.pimalaya.sync.SyncCoordinator;
import org.pimalaya.sync.SyncPasses;
import org.pimalaya.ui.Host;
import org.pimalaya.ui.StubHost;
import org.pimalaya.ui.SyncDock;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

/** A browser grant signing in another address than the account's. */
@RunWith(RobolectricTestRunner.class)
public class WrongGrantTest {
    private static final String ALICE = "alice@gmail.com";
    private static final String BOB = "bob@gmail.com";
    private static final String SCOPE = "https://mail.google.com/";

    private FlowHost host;
    private SecureStore store;
    private OnboardingFlow flow;

    @Before
    public void setUp() throws Exception {
        FakeAndroidKeyStore.install();
        Context context = RuntimeEnvironment.getApplication();
        host = Robolectric.buildActivity(FlowHost.class).get();
        host.setTheme(R.style.AppTheme);
        host.setContentView(R.layout.activity_main);
        // NOTE: discovery and the identity read run on io; nothing runs there.
        java.lang.reflect.Field io = Host.class.getField("io");
        io.setAccessible(true);
        io.set(host, new Parked());

        PimdirDb pimdir = new PimdirDb(context);
        store = new SecureStore(context);
        store.connect(
                ALICE,
                PimDomain.MAIL,
                "imaps://imap.gmail.com:993",
                "smtps://smtp.gmail.com:465",
                AccountCredential.oauth(tokens("old"), grant()));
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
        oauth.onBusy = () -> {};
        oauth.onAborted = () -> {};
        flow =
                new OnboardingFlow(
                        host, parts, accounts, new SyncCoordinator(host, parts, host), host, oauth);
        flow.bind();
        flow.openOn(AccountEntry.find(accounts, ALICE));
    }

    private static OauthTokens tokens(String access) {
        return new OauthTokens(access, "Bearer", 3600, access + "-refresh", SCOPE, 0);
    }

    private static OauthGrant grant() {
        return new OauthGrant(Oauth.GOOGLE_TOKEN_ENDPOINT, Oauth.GOOGLE_CLIENT_ID, null, SCOPE);
    }

    @Test
    public void aGrantForAnotherAddressIsRefusedAndTheAccountKeepsItsOldSignIn() {
        host.findViewById(R.id.signin_continue).performClick();

        flow.land(ALICE, BOB, tokens("new"), grant());

        AccountEntry stored = AccountEntry.find(store.loadAll(), ALICE);
        assertEquals("old", stored.credential(PimDomain.MAIL).secret);
        String refusal = host.getString(R.string.grant_wrong_account, BOB, ALICE);
        assertTrue(
                "the page says both addresses",
                texts(host.findViewById(R.id.signin_container)).contains(refusal));
    }

    /** Every text a view shows, its children's included. */
    private static List<String> texts(View view) {
        List<String> texts = new ArrayList<>();
        if (view instanceof TextView && view.getVisibility() == View.VISIBLE) {
            texts.add(((TextView) view).getText().toString());
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int index = 0; index < group.getChildCount(); index++) {
                texts.addAll(texts(group.getChildAt(index)));
            }
        }
        return texts;
    }

    /** An executor holding its tasks, so nothing reaches the network. */
    private static final class Parked extends AbstractExecutorService {
        @Override
        public void execute(Runnable task) {}

        @Override
        public void shutdown() {}

        @Override
        public List<Runnable> shutdownNow() {
            return List.of();
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

    /** A screenless activity for the flow, its auth steps remembered. */
    public static class FlowHost extends StubHost implements SetupShell, SyncCoordinator.Screens {
        private int step;

        @Override
        public void showAuth(int step) {
            this.step = step;
        }

        @Override
        public int authStep() {
            return step;
        }

        @Override
        public void askMirrors(Set<PhoneMirror> wanted, Consumer<Set<PhoneMirror>> done) {}

        @Override
        public void askNotifications(Consumer<Boolean> done) {}

        @Override
        public void reload(PimDomain domain) {}

        @Override
        public void filterChanged() {}

        @Override
        public void backToAccount(String email) {}

        @Override
        public PimDomain shown() {
            return null;
        }

        @Override
        public SyncDock dock() {
            return null;
        }

        @Override
        public void syncing(boolean active) {}

        @Override
        public void land(PimDomain domain) {}

        @Override
        public void renewed(AccountEntry updated) {}

        @Override
        public boolean backgroundRunning() {
            return false;
        }

        @Override
        public void bodiesChanged() {}

        @Override
        public long contactsIngested() {
            return 0;
        }

        @Override
        public long calendarsIngested() {
            return 0;
        }
    }
}
