package org.pimalaya.setup;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.Intent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
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
import org.robolectric.Shadows;

/**
 * A Google calendar signing in again asks for its events and its calendar
 * list, not every calendar, and lands only when both come back.
 */
@RunWith(RobolectricTestRunner.class)
public class NarrowCalendarGrantTest {
    private static final String ALICE = "alice@gmail.com";
    private static final String CALENDAR = "https://www.googleapis.com/auth/calendar";
    private static final String EVENTS = "https://www.googleapis.com/auth/calendar.events";
    private static final String LIST =
            "https://www.googleapis.com/auth/calendar.calendarlist.readonly";
    private static final String TASKS = "https://www.googleapis.com/auth/tasks";

    private WrongGrantTest.FlowHost host;
    private SecureStore store;
    private OnboardingFlow flow;

    @Before
    public void setUp() throws Exception {
        FakeAndroidKeyStore.install();
        Context context = RuntimeEnvironment.getApplication();
        host = Robolectric.buildActivity(WrongGrantTest.FlowHost.class).get();
        host.setTheme(R.style.AppTheme);
        host.setContentView(R.layout.activity_main);
        // NOTE: discovery and the identity read run on io; nothing runs there.
        java.lang.reflect.Field io = Host.class.getField("io");
        io.setAccessible(true);
        io.set(host, new Parked());

        // NOTE: a grant from before the app narrowed its calendar scope.
        String older = CALENDAR + " " + TASKS;
        PimdirDb pimdir = new PimdirDb(context);
        store = new SecureStore(context);
        store.connect(
                ALICE,
                PimDomain.CALENDAR,
                AccountClient.googleBase(ALICE),
                null,
                AccountCredential.oauth(tokens("old", older), grant(older)));
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

    private static OauthTokens tokens(String access, String scope) {
        return new OauthTokens(access, "Bearer", 3600, access + "-refresh", scope, 0);
    }

    private static OauthGrant grant(String scope) {
        return new OauthGrant(Oauth.GOOGLE_TOKEN_ENDPOINT, Oauth.GOOGLE_CLIENT_ID, null, scope);
    }

    @Test
    public void theGrantAsksForTheEventsAndTheListInPlaceOfEveryCalendar() {
        host.findViewById(R.id.signin_continue).performClick();
        GoogleNoticeTest.agree();

        Intent browser = Shadows.shadowOf(host).getNextStartedActivity();
        String scope = browser.getData().getQueryParameter("scope");
        List<String> asked = Arrays.asList(scope.split("[ +]"));
        assertTrue(scope, asked.containsAll(List.of(EVENTS, LIST, TASKS)));
        assertFalse(scope, asked.contains(CALENDAR));
    }

    /** The older grant's full calendar scope does not ride into the token. */
    @Test
    public void theGrantDoesNotTakeBackTheOlderGrantsScopes() {
        host.findViewById(R.id.signin_continue).performClick();
        GoogleNoticeTest.agree();

        Intent browser = Shadows.shadowOf(host).getNextStartedActivity();
        String url = browser.getData().toString();
        assertEquals(url, null, browser.getData().getQueryParameter("include_granted_scopes"));
    }

    /** The user unticked the calendar list on Google's consent page. */
    @Test
    public void aGrantWithoutTheCalendarListLeavesTheCalendarOut() {
        host.findViewById(R.id.signin_continue).performClick();
        GoogleNoticeTest.agree();

        String granted = EVENTS + " " + TASKS;
        flow.land(ALICE, null, tokens("new", granted), grant(granted));

        AccountEntry stored = AccountEntry.find(store.loadAll(), ALICE);
        assertEquals("old", stored.credential(PimDomain.CALENDAR).secret);
        String refusal =
                host.getString(
                        R.string.signin_not_allowed_for, host.getString(PimDomain.CALENDAR.label));
        assertTrue(texts(host.findViewById(R.id.signin_container)).contains(refusal));
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
