package org.pimalaya;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.accounts.Account;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.provider.CalendarContract;
import android.provider.ContactsContract;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.pimalaya.file.FilesList;
import org.pimalaya.mail.MailBodies;
import org.pimalaya.mail.MailEngine;
import org.pimalaya.mail.MailOwed;
import org.pimalaya.mail.MailPoison;
import org.pimalaya.mail.MessageView;
import org.pimalaya.pimdir.CacheSweep;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

/**
 * The app's surface against what another app, a server or a hostile
 * message hands it: links in a message, attachment names, the exported
 * sync adapters, accounts copied without their key, bodies that kill the
 * app, and what a killed run leaves in the cache.
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
    public void aTappedWebOrMailLinkOpensAsABrowserWould() {
        Intent web = MessageView.linkIntent(Uri.parse("https://example.org/a"), true, true);
        Intent mail = MessageView.linkIntent(Uri.parse("mailto:bob@example.org"), true, true);

        assertNotNull(web);
        assertEquals(Intent.ACTION_VIEW, web.getAction());
        assertTrue(web.hasCategory(Intent.CATEGORY_BROWSABLE));
        assertNull(web.getComponent());
        assertNull(web.getSelector());
        assertNotNull(mail);
        assertNotNull(MessageView.linkIntent(Uri.parse("HTTP://example.org"), true, true));
    }

    @Test
    public void aLinkNotTappedInTheMessageItselfOpensNothing() {
        Uri tracker = Uri.parse("https://tracker.example/?id=victim");

        // A meta refresh, then an iframe.
        assertNull(MessageView.linkIntent(tracker, false, true));
        assertNull(MessageView.linkIntent(tracker, true, false));
        assertNull(MessageView.linkIntent(Uri.parse("someapp://action?x=1"), true, true));
        assertNull(MessageView.linkIntent(Uri.parse("org.pimalaya:/oauth2redirect"), true, true));
        assertNull(MessageView.linkIntent(Uri.parse("intent:#Intent;end"), true, true));
        assertNull(MessageView.linkIntent(Uri.parse("file:///data/x"), true, true));
        assertNull(MessageView.linkIntent(Uri.parse("relative"), true, true));
    }

    @Test
    public void anAttachmentNameDropsWhatWouldSpoofOrBreakIt() {
        assertEquals("invoicefdp.apk", OpenedFiles.safe("invoice\u202Efdp.apk"));
        assertEquals("a_b_c", OpenedFiles.safe("a/b\\c"));
        assertEquals("x", OpenedFiles.safe("\u0000\u200Ex"));
        assertEquals("file.hidden", OpenedFiles.safe(".hidden"));
    }

    @Test
    public void anAttachmentNameFitsTheFileSystemInBytes() {
        // NOTE: 120 three-byte characters, 360 bytes, which ext4 refuses.
        String name = OpenedFiles.safe("\u6587".repeat(120) + ".pdf");

        assertTrue(name.getBytes(StandardCharsets.UTF_8).length <= 200);
        assertTrue(name.endsWith(".pdf"));

        String smile = new String(Character.toChars(0x1F600));
        String cut = OpenedFiles.safe("a" + smile.repeat(100));
        assertTrue(cut.getBytes(StandardCharsets.UTF_8).length <= 200);
        assertFalse(Character.isHighSurrogate(cut.charAt(cut.length() - 1)));
        assertEquals(cut.codePointCount(0, cut.length()) * 2 - 1, cut.length());
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
    public void aReadThatKilledTheAppIsGivenUpAfterItsAttempts() {
        String key = MailOwed.key("inbox", "m1");

        for (int attempt = 0; attempt < MailBodies.ATTEMPTS; attempt++) {
            assertFalse(MailPoison.poisoned(context, key));
            // NOTE: begun and never ended, as a crash leaves it.
            assertTrue(MailPoison.begin(context, key));
        }

        assertTrue(MailPoison.poisoned(context, key));
        assertFalse(MailPoison.begin(context, key));
    }

    @Test
    public void aReadThatEndsLeavesNoMark() {
        String key = MailOwed.key("inbox", "m2");

        for (int read = 0; read < MailBodies.ATTEMPTS + 2; read++) {
            assertTrue(MailPoison.begin(context, key));
            MailPoison.end(context, key);
        }

        assertFalse(MailPoison.poisoned(context, key));
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
        assertEquals("*@example.org", Accounts.redacted("bob@example.org"));
        assertEquals("*", Accounts.redacted("bob"));
        assertEquals("?", Accounts.redacted(null));
    }
}
