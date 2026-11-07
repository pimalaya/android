package org.pimalaya.sync;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import java.util.List;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.pimalaya.FakeAndroidKeyStore;
import org.pimalaya.account.AccountCredential;
import org.pimalaya.account.AccountEntry;
import org.pimalaya.account.PimDomain;
import org.pimalaya.account.SecureStore;
import org.pimalaya.account.SyncScope;
import org.pimalaya.calendar.EventStore;
import org.pimalaya.client.Account;
import org.pimalaya.client.AccountClient;
import org.pimalaya.client.Calendar;
import org.pimalaya.client.CalendarClient;
import org.pimalaya.client.Clients;
import org.pimalaya.client.ContactClient;
import org.pimalaya.client.MailClient;
import org.pimalaya.client.OfflineClient;
import org.pimalaya.client.PimalayaException;
import org.pimalaya.client.Transport;
import org.pimalaya.contact.CardStore;
import org.pimalaya.mail.MailStore;
import org.pimalaya.pimdir.PimdirDb;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

/** An account refused for its sign-in, as the sync records it. */
@RunWith(RobolectricTestRunner.class)
public class SignInRefusalsTest {
    private static final String EMAIL = "jane@example.org";

    private Context context;
    private Refusing dav;
    private SyncPasses passes;
    private AccountEntry account;

    /** A calendar server refusing the password while told to. */
    private static final class Refusing extends CalendarClient {
        boolean refusing;

        @Override
        public List<Calendar> listCalendars(Transport transport, Account account) {
            if (refusing) {
                throw new PimalayaException("WebDAV server returned HTTP 401", 401);
            }
            return List.of();
        }
    }

    @Before
    public void setUp() {
        FakeAndroidKeyStore.install();
        context = RuntimeEnvironment.getApplication();
        PimdirDb pimdir = new PimdirDb(context);
        SecureStore store = new SecureStore(context);
        store.connect(
                EMAIL,
                PimDomain.CALENDAR,
                "https://dav.example.org/",
                null,
                AccountCredential.password(EMAIL, "changed elsewhere"));
        account = store.accountsFor(PimDomain.CALENDAR).get(0);
        dav = new Refusing();
        Clients clients =
                new Clients(
                        new AccountClient(),
                        new ContactClient(),
                        dav,
                        new MailClient(),
                        new OfflineClient());
        passes =
                new SyncPasses(
                        context,
                        new SyncPasses.Parts(
                                pimdir,
                                new CardStore(context, pimdir),
                                store,
                                new MailStore(context, pimdir),
                                new EventStore(context, pimdir),
                                clients),
                        SyncPasses.HEADLESS);
    }

    @Test
    public void aRefusalRecordedByTheSyncIsClearedByTheNextSuccessfulSync() {
        dav.refusing = true;
        assertNotNull(passes.fetchCalendars(account, SyncScope.all(context)));
        assertTrue("the 401 recorded", SignInRefusals.refused(context, EMAIL));

        dav.refusing = false;
        assertNull(passes.fetchCalendars(account, SyncScope.all(context)));
        assertFalse(
                "cleared by the sync that signed in", SignInRefusals.refused(context, EMAIL));
    }

    @Test
    public void anotherPlaceRefusingKeepsTheAccountRefused() {
        SignInRefusals.note(
                context,
                EMAIL,
                SignInRefusals.SENDING,
                new PimalayaException("535 Authentication failed", 401));

        dav.refusing = false;
        assertNull(passes.fetchCalendars(account, SyncScope.all(context)));
        assertTrue(SignInRefusals.refused(context, EMAIL));

        SignInRefusals.note(
                context, EMAIL, SignInRefusals.SENDING, new PimalayaException("Connection reset"));
        assertTrue(
                "a failure not about the sign-in says nothing of it",
                SignInRefusals.refused(context, EMAIL));
    }
}
