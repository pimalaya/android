package org.pimalaya.sync;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.TimeUnit;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.pimalaya.FakeAndroidKeyStore;
import org.pimalaya.account.AccountCredential;
import org.pimalaya.account.AccountEntry;
import org.pimalaya.account.PimDomain;
import org.pimalaya.account.SecureStore;
import org.pimalaya.calendar.EventStore;
import org.pimalaya.client.Clients;
import org.pimalaya.contact.CardStore;
import org.pimalaya.mail.MailStore;
import org.pimalaya.pimdir.PimdirDb;
import org.pimalaya.setup.WrongGrantTest;
import org.pimalaya.ui.Host;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

/**
 * A freshly connected account's tab waits on its first sync, which holds the
 * tab's add button away until it runs.
 */
@RunWith(RobolectricTestRunner.class)
public class FirstSyncAheadTest {
    private static final String ALICE = "alice@example.org";

    private SyncCoordinator sync;
    private AccountEntry alice;

    @Before
    public void setUp() throws Exception {
        FakeAndroidKeyStore.install();
        Context context = RuntimeEnvironment.getApplication();
        ContactsHost host = Robolectric.buildActivity(ContactsHost.class).get();
        // NOTE: the first sync's pass runs on io; nothing runs there.
        java.lang.reflect.Field io = Host.class.getField("io");
        io.setAccessible(true);
        io.set(host, new Parked());

        PimdirDb pimdir = new PimdirDb(context);
        SecureStore store = new SecureStore(context);
        store.connect(
                ALICE,
                PimDomain.CONTACTS,
                "https://dav.example.org/",
                null,
                AccountCredential.password(ALICE, "secret"));
        SyncPasses.Parts parts =
                new SyncPasses.Parts(
                        pimdir,
                        new CardStore(context, pimdir),
                        store,
                        new MailStore(context, pimdir),
                        new EventStore(context, pimdir),
                        Clients.real());
        sync = new SyncCoordinator(host, parts, host);
        alice = AccountEntry.find(store.loadAll(), ALICE);
    }

    @Test
    public void aConnectedAccountsTabWaitsOnItsFirstSyncTillItRuns() {
        sync.syncConnected(alice);
        assertTrue(sync.firstSyncAhead(PimDomain.CONTACTS));
        assertFalse("a domain the account does not cover", sync.firstSyncAhead(PimDomain.MAIL));

        sync.firstSyncIfOwed(PimDomain.CONTACTS);
        assertFalse("run, whatever it ends with", sync.firstSyncAhead(PimDomain.CONTACTS));

        sync.syncConnected(alice);
        assertTrue("connected again, owed anew", sync.firstSyncAhead(PimDomain.CONTACTS));
    }

    /** The flow's host, on the contacts tab. */
    public static class ContactsHost extends WrongGrantTest.FlowHost {
        @Override
        public PimDomain shown() {
            return PimDomain.CONTACTS;
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
