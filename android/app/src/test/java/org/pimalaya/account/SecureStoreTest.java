package org.pimalaya.account;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.SharedPreferences;
import java.util.List;
import java.util.Set;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.pimalaya.FakeAndroidKeyStore;
import org.pimalaya.client.OauthGrant;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

/**
 * The encrypted account store's plaintext, which installed apps already hold.
 *
 * <p>The same accounts write the same bytes, and a store the previous release
 * wrote (text captured on it) reads back to the same accounts.
 */
@RunWith(RobolectricTestRunner.class)
public class SecureStoreTest {
    private static final String TOKEN = "https://auth.example.org/token";

    /** What the previous release wrote for the accounts {@link #store} adds. */
    private static final String WRITTEN =
            "[{\"email\":\"jane@example.org\",\"connections\":{\"mail\":{\"baseUrl\":\"imaps:\\/\\/imap.example.org:993\",\"credential\":\"cred-mail\",\"submitUrl\":\"smtps:\\/\\/smtp.example.org:465\"},\"contacts\":{\"baseUrl\":\"https:\\/\\/dav.example.org\\/\",\"credential\":\"cred-dav\"},\"calendar\":{\"baseUrl\":\"https:\\/\\/dav.example.org\\/\",\"credential\":\"cred-dav\"}},"
                    + "\"credentials\":{\"cred-dav\":{\"login\":\"\",\"secret\":\"access-d\",\"refreshToken\":\"refresh-d\",\"tokenEndpoint\":\"https:\\/\\/auth.example.org\\/token\",\"clientId\":\"client\"},\"cred-mail\":{\"login\":\"\",\"secret\":\"access-m\",\"refreshToken\":\"refresh-m\",\"tokenEndpoint\":\"https:\\/\\/auth.example.org\\/token\",\"clientId\":\"client\",\"clientSecret\":\"s3cret\",\"scope\":\"mail offline_access\"}}},"
                    + "{\"email\":\"bob@example.org\",\"connections\":{\"contacts\":{\"baseUrl\":\"https:\\/\\/dav.example.net\\/\",\"credential\":\"cred-pw\"}},"
                    + "\"credentials\":{\"cred-pw\":{\"login\":\"bob\",\"secret\":\"pw\"}}},"
                    + "{\"email\":\"carol@example.org\",\"connections\":{\"calendar\":{\"baseUrl\":\"https:\\/\\/cal.example.org\\/\",\"credential\":\"cred-norefresh\"}},"
                    + "\"credentials\":{\"cred-norefresh\":{\"login\":\"\",\"secret\":\"access-c\"}}},"
                    + "{\"email\":\"dave@example.org\",\"connections\":{},"
                    + "\"credentials\":{}}]";

    private Context context;

    @Before
    public void setUp() {
        FakeAndroidKeyStore.install();
        context = RuntimeEnvironment.getApplication();
    }

    private SharedPreferences prefs() {
        return context.getSharedPreferences("pimalaya.account", Context.MODE_PRIVATE);
    }

    /** Adds every kind of account the store holds, in a fixed order. */
    private static void store(SecureStore store) {
        AccountCredential mail =
                new AccountCredential(
                        "cred-mail",
                        "",
                        "access-m",
                        new AccountCredential.Renewal(
                                "refresh-m",
                                new OauthGrant(TOKEN, "client", "s3cret", "mail offline_access")));
        AccountCredential dav =
                new AccountCredential(
                        "cred-dav",
                        "",
                        "access-d",
                        new AccountCredential.Renewal(
                                "refresh-d", new OauthGrant(TOKEN, "client", null, null)));
        store.add(
                AccountEntry.empty("jane@example.org")
                        .with(
                                PimDomain.MAIL,
                                "imaps://imap.example.org:993",
                                "smtps://smtp.example.org:465",
                                mail)
                        .with(PimDomain.CONTACTS, "https://dav.example.org/", null, dav)
                        .with(PimDomain.CALENDAR, "https://dav.example.org/", null, dav));
        store.add(
                AccountEntry.empty("bob@example.org")
                        .with(
                                PimDomain.CONTACTS,
                                "https://dav.example.net/",
                                null,
                                new AccountCredential("cred-pw", "bob", "pw", null)));
        // NOTE: a grant the server issued no refresh token for keeps nothing
        // to renew it with on disk.
        store.add(
                AccountEntry.empty("carol@example.org")
                        .with(
                                PimDomain.CALENDAR,
                                "https://cal.example.org/",
                                null,
                                new AccountCredential(
                                        "cred-norefresh",
                                        "",
                                        "access-c",
                                        new AccountCredential.Renewal(
                                                null,
                                                new OauthGrant(TOKEN, "client", "x", "calendar")))));
        store.add(AccountEntry.empty("dave@example.org"));
    }

    @Test
    public void theSameAccountsWriteTheSameBytes() throws Exception {
        store(new SecureStore(context));

        String plaintext =
                SecureStore.open(prefs().getString("payload", null), prefs().getString("iv", null));
        assertEquals(WRITTEN, plaintext);
    }

    @Test
    public void aStoreThePreviousReleaseWroteReadsBack() throws Exception {
        String[] sealed = SecureStore.seal(WRITTEN);
        prefs().edit().putString("payload", sealed[0]).putString("iv", sealed[1]).commit();

        List<AccountEntry> entries = new SecureStore(context).loadAll();

        assertEquals(4, entries.size());
        AccountEntry jane = entries.get(0);
        assertEquals("jane@example.org", jane.email);
        assertEquals(Set.of(PimDomain.MAIL, PimDomain.CONTACTS, PimDomain.CALENDAR), jane.domains());
        assertEquals("imaps://imap.example.org:993", jane.connection(PimDomain.MAIL).baseUrl);
        assertEquals("smtps://smtp.example.org:465", jane.connection(PimDomain.MAIL).submitUrl);
        assertEquals("https://dav.example.org/", jane.connection(PimDomain.CALENDAR).baseUrl);
        assertNull(jane.connection(PimDomain.CONTACTS).submitUrl);
        assertEquals(2, jane.credentials().size());

        AccountCredential mail = jane.credential(PimDomain.MAIL);
        assertEquals("cred-mail", mail.id);
        assertEquals("", mail.login);
        assertEquals("access-m", mail.secret);
        assertEquals("refresh-m", mail.refreshToken());
        assertEquals(new OauthGrant(TOKEN, "client", "s3cret", "mail offline_access"), mail.renewal.grant());
        assertTrue(mail.renewable());

        AccountCredential dav = jane.credential(PimDomain.CONTACTS);
        assertEquals("cred-dav", dav.id);
        assertEquals(dav, jane.credential(PimDomain.CALENDAR));
        assertEquals("access-d", dav.secret);
        assertEquals("refresh-d", dav.refreshToken());
        assertEquals(new OauthGrant(TOKEN, "client", null, null), dav.renewal.grant());

        AccountCredential bob = entries.get(1).credential(PimDomain.CONTACTS);
        assertEquals("bob@example.org", entries.get(1).email);
        assertEquals("cred-pw", bob.id);
        assertEquals("bob", bob.login);
        assertEquals("pw", bob.secret);
        assertNull(bob.renewal);
        assertFalse(bob.renewable());

        AccountCredential carol = entries.get(2).credential(PimDomain.CALENDAR);
        assertEquals("carol@example.org", entries.get(2).email);
        assertEquals("access-c", carol.secret);
        assertNull(carol.renewal);
        assertFalse(carol.renewable());

        assertEquals("dave@example.org", entries.get(3).email);
        assertTrue(entries.get(3).domains().isEmpty());
        assertTrue(entries.get(3).credentials().isEmpty());
    }

    @Test
    public void aStoreReadBackWritesTheSameBytes() throws Exception {
        String[] sealed = SecureStore.seal(WRITTEN);
        prefs().edit().putString("payload", sealed[0]).putString("iv", sealed[1]).commit();

        SecureStore store = new SecureStore(context);
        store.remove("nobody@example.org");

        assertEquals(
                WRITTEN,
                SecureStore.open(prefs().getString("payload", null), prefs().getString("iv", null)));
    }
}
