package org.pimalaya.sync;

import static org.junit.Assert.assertEquals;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.pimalaya.FakeAndroidKeyStore;
import org.pimalaya.account.AccountCredential;
import org.pimalaya.account.AccountEntry;
import org.pimalaya.account.PimDomain;
import org.pimalaya.account.SecureStore;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

/**
 * A credential is renewed once however many workers find it expired: a
 * rotating provider retires the refresh token on its first use, so the
 * others reuse that renewal instead of spending the retired token.
 */
@RunWith(RobolectricTestRunner.class)
public class TokenRenewalTest {
    private static final String EMAIL = "jane@example.org";

    private SecureStore store;

    @Before
    public void setUp() {
        FakeAndroidKeyStore.install();
        store = new SecureStore(RuntimeEnvironment.getApplication());
        store.add(
                AccountEntry.of(
                        EMAIL,
                        PimDomain.MAIL,
                        "imaps://imap.example.org:993",
                        AccountCredential.oauth(
                                "access-0",
                                "refresh-0",
                                "https://auth.example.org/token",
                                "client",
                                null,
                                null)));
    }

    @Test
    public void workersHoldingOneStaleCopyRenewItOnce() throws Exception {
        AccountEntry stale = store.find(EMAIL);
        AtomicInteger requests = new AtomicInteger();
        CountDownLatch started = new CountDownLatch(1);
        TokenRenewal.Refresh refresh =
                credential -> {
                    int request = requests.incrementAndGet();
                    started.countDown();
                    assertEquals("refresh-0", credential.refreshToken);
                    try {
                        Thread.sleep(200);
                    } catch (InterruptedException ignored) {
                    }
                    return new TokenRenewal.Renewed("access-" + request, "refresh-" + request);
                };

        ExecutorService pool = Executors.newFixedThreadPool(3);
        Future<AccountEntry> first =
                pool.submit(() -> TokenRenewal.renew(store, stale, PimDomain.MAIL, refresh));
        started.await(5, TimeUnit.SECONDS);
        Future<AccountEntry> second =
                pool.submit(() -> TokenRenewal.renew(store, stale, PimDomain.MAIL, refresh));
        Future<AccountEntry> third =
                pool.submit(() -> TokenRenewal.renew(store, stale, PimDomain.MAIL, refresh));

        for (Future<AccountEntry> renewed : java.util.List.of(first, second, third)) {
            AccountCredential credential = renewed.get(5, TimeUnit.SECONDS).credential(PimDomain.MAIL);
            assertEquals("access-1", credential.secret);
            assertEquals("refresh-1", credential.refreshToken);
        }
        pool.shutdown();

        assertEquals(1, requests.get());
        assertEquals("refresh-1", store.find(EMAIL).credential(PimDomain.MAIL).refreshToken);
    }

    @Test
    public void aRenewalWithoutANewRefreshTokenKeepsTheOld() {
        AccountEntry renewed =
                TokenRenewal.renew(
                        store,
                        store.find(EMAIL),
                        PimDomain.MAIL,
                        credential -> new TokenRenewal.Renewed("access-1", null));

        assertEquals("access-1", renewed.credential(PimDomain.MAIL).secret);
        assertEquals("refresh-0", store.find(EMAIL).credential(PimDomain.MAIL).refreshToken);
    }
}
