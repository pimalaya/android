package org.pimalaya.mail;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.SharedPreferences;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.pimalaya.account.MergedFilter;
import org.pimalaya.account.PimDomain;
import org.pimalaya.client.Mailbox;
import org.pimalaya.pimdir.PimdirCollections;
import org.pimalaya.pimdir.PimdirDb;
import org.pimalaya.pimdir.PimdirSummary;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

import java.util.List;

/**
 * The mail settings kept in shared preferences, read and written under the
 * file names and keys an installed version wrote them with: a value written
 * before an upgrade is read after it.
 */
@RunWith(RobolectricTestRunner.class)
public class MailPolicyTest {
    private static final String EMAIL = "john@example.org";

    private Context context;
    private MailStore store;
    private String accountId;

    @Before
    public void setUp() {
        context = RuntimeEnvironment.getApplication();
        store = new MailStore(context, new PimdirDb(context));
        accountId = store.accountIdOf(EMAIL);
    }

    private SharedPreferences prefs(String name) {
        return context.getSharedPreferences(name, Context.MODE_PRIVATE);
    }

    private static List<Mailbox> roster() {
        return List.of(
                new Mailbox("INBOX", "inbox"),
                new Mailbox("Sent", "sent"),
                new Mailbox("Bin", "trash"));
    }

    @Test
    public void theAllMailMarkIsReadAndWrittenAsBefore() {
        prefs("mail-all-mail").edit().putBoolean(accountId, true).commit();
        assertTrue(MailPolicy.keepsAllMail(context, accountId));

        MailPolicy.markAllMail(context, accountId, false);
        assertFalse(prefs("mail-all-mail").contains(accountId));
        MailPolicy.markAllMail(context, accountId, true);
        assertTrue(prefs("mail-all-mail").getBoolean(accountId, false));
    }

    @Test
    public void theAccountWideMarkIsReadAndWrittenAsBefore() {
        prefs("mail-account-wide").edit().putBoolean(accountId, true).commit();
        assertTrue(MailPolicy.accountWide(context, accountId));

        MailPolicy.markAccountWide(context, accountId, false);
        assertFalse(prefs("mail-account-wide").contains(accountId));
        MailPolicy.markAccountWide(context, accountId, true);
        assertTrue(prefs("mail-account-wide").getBoolean(accountId, false));
    }

    @Test
    public void theMarkingMarkIsReadAndWrittenAsBefore() {
        assertTrue(store.policy().expungesOne(EMAIL));
        prefs("mail-marks-deleted").edit().putBoolean(accountId, true).commit();
        assertFalse(store.policy().expungesOne(EMAIL));

        MailPolicy.markExpungesOne(context, accountId, true);
        assertFalse(prefs("mail-marks-deleted").contains(accountId));
        MailPolicy.markExpungesOne(context, accountId, false);
        assertTrue(prefs("mail-marks-deleted").getBoolean(accountId, false));
    }

    @Test
    public void theTrashIsReadAndWrittenAsBefore() {
        prefs("mail-trash").edit().putString(EMAIL, "Old bin").commit();
        assertEquals("Old bin", store.policy().trashOf(EMAIL));

        store.replaceMailboxes(EMAIL, roster());
        assertEquals("Bin", prefs("mail-trash").getString(EMAIL, null));

        store.forget(EMAIL);
        assertFalse(prefs("mail-trash").contains(EMAIL));
    }

    @Test
    public void theSentAliasIsReadAndWrittenAsBefore() {
        prefs("mail-sent-alias").edit().putString("filed@gmail", "staged@example.org").commit();
        assertEquals("staged@example.org", store.policy().stagedSentCopy("filed@gmail"));

        store.policy().aliasSentCopy("mine@example.org", "theirs@gmail");
        assertEquals("mine@example.org", prefs("mail-sent-alias").getString("theirs@gmail", null));
        store.policy().forgetSentAlias("theirs@gmail");
        assertNull(prefs("mail-sent-alias").getString("theirs@gmail", null));
    }

    @Test
    public void theLinksRepairedMarkIsWrittenAsBefore() {
        assertEquals(0, store.repairLinks(accountId));
        assertTrue(prefs("mail-links-repaired").getBoolean(accountId, false));
    }

    @Test
    public void theInboxOnlyMarkIsWrittenAsBefore() {
        store.replaceMailboxes(EMAIL, roster());
        assertTrue(prefs("mail-inbox-only").getBoolean(accountId, false));

        store.forget(EMAIL);
        assertFalse(prefs("mail-inbox-only").contains(accountId));
    }

    @Test
    public void theInboxOnlyMarkIsReadAsBefore() {
        // NOTE: a roster already stored, so the replace marks nothing itself.
        new PimdirCollections(new PimdirDb(context), context)
                .replace(
                        EMAIL,
                        PimdirSummary.MAIL,
                        MailEngine.collectionsOf(
                                EMAIL, accountId, List.of(new Mailbox("INBOX", "inbox"))));
        prefs("mail-inbox-only").edit().putBoolean(accountId, true).commit();

        store.replaceMailboxes(EMAIL, roster());

        MergedFilter filter = MergedFilter.of(context, PimDomain.MAIL);
        assertTrue(filter.chosen(store.collectionOf(EMAIL, "INBOX")));
        assertFalse(filter.chosen(store.collectionOf(EMAIL, "Sent")));
    }
}
