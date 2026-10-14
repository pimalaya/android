package org.pimalaya;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.accounts.Account;
import android.content.Context;
import android.content.SharedPreferences;
import android.provider.CalendarContract;
import android.provider.ContactsContract;
import java.io.File;
import java.nio.file.Files;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.pimalaya.account.AccountEntry;
import org.pimalaya.account.SecureStore;
import org.pimalaya.file.FilesList;
import org.pimalaya.mail.MailEngine;
import org.pimalaya.phone.Accounts;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

/**
 * The app's surface against what another app or a server hands it.
 *
 * <p>The exported sync adapters, accounts copied without their key, a killed
 * run's cache leftovers. Hostile messages are the mail package's
 * ({@link org.pimalaya.mail.HardeningTest}).
 */
@RunWith(RobolectricTestRunner.class)
public class HardeningTest {
    private Context context;

    @Before
    public void setUp() {
        FakeAndroidKeyStore.install();
        context = RuntimeEnvironment.getApplication();
    }

    @Test
    public void theSyncAdaptersServeTheirOwnAccountsAndAuthorityAlone() {
        Account own = new Account("bob@example.org", Accounts.TYPE);
        Account other = new Account("bob@example.org", "com.google");

        assertTrue(SyncService.serves(own, ContactsContract.AUTHORITY));
        assertFalse(SyncService.serves(own, CalendarContract.AUTHORITY));
        assertFalse(SyncService.serves(other, ContactsContract.AUTHORITY));
        assertFalse(SyncService.serves(null, ContactsContract.AUTHORITY));
        assertTrue(CalendarSyncService.serves(own, CalendarContract.AUTHORITY));
        assertFalse(CalendarSyncService.serves(own, ContactsContract.AUTHORITY));
        assertFalse(CalendarSyncService.serves(other, CalendarContract.AUTHORITY));
        assertFalse(CalendarSyncService.serves(null, CalendarContract.AUTHORITY));
    }

    @Test
    public void accountsSealedUnderAnotherKeyAreDroppedOnceAndSaidSo() {
        SecureStore store = new SecureStore(context);
        store.add(AccountEntry.empty("bob@example.org"));
        assertEquals(1, store.loadAll().size());

        // NOTE: what a device transfer brings: a blob the key on this phone
        // did not seal, which opens to a failed tag.
        SharedPreferences prefs =
                context.getSharedPreferences("pimalaya.account", Context.MODE_PRIVATE);
        String payload = prefs.getString("payload", "");
        char flipped = payload.charAt(0) == 'A' ? 'B' : 'A';
        prefs.edit().putString("payload", flipped + payload.substring(1)).commit();

        assertTrue(store.loadAll().isEmpty());
        assertTrue(store.takeLost());
        assertFalse(store.takeLost());
        assertTrue(store.loadAll().isEmpty());
        assertFalse(prefs.contains("payload"));
    }

    @Test
    public void theCacheLosesWhatAKilledRunLeftAndKeepsARunningOne() throws Exception {
        File cache = context.getCacheDir();
        File old = new File(new File(cache, MailEngine.SCRATCH), "old");
        File fresh = new File(new File(cache, MailEngine.SCRATCH), "fresh");
        old.mkdirs();
        fresh.mkdirs();
        Files.write(new File(old, "1.eml").toPath(), new byte[] {1});
        File staged = File.createTempFile(FilesList.STAGED, null, cache);
        File kept = new File(cache, "other.tmp");
        Files.write(kept.toPath(), new byte[] {1});
        long now = System.currentTimeMillis();
        old.setLastModified(now - 2 * CacheSweep.AGE);
        staged.setLastModified(now - 2 * CacheSweep.AGE);
        kept.setLastModified(now - 2 * CacheSweep.AGE);

        CacheSweep.run(context, now);

        assertFalse(old.exists());
        assertTrue(fresh.exists());
        assertFalse(staged.exists());
        assertTrue(kept.exists());
    }

    @Test
    public void aReleaseLogNamesTheDomainAlone() {
        assertEquals("*@example.org", AccountEntry.redacted("bob@example.org"));
        assertEquals("*", AccountEntry.redacted("bob"));
        assertEquals("?", AccountEntry.redacted(null));
    }
}
