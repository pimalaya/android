package org.pimalaya.mail;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import io.requery.android.database.sqlite.SQLiteDatabase;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.pimalaya.account.MergedFilter;
import org.pimalaya.account.PimDomain;
import org.pimalaya.client.Mailbox;
import org.pimalaya.pimdir.PimdirCollections;
import org.pimalaya.pimdir.PimdirDb;
import org.pimalaya.pimdir.PimdirItems;
import org.pimalaya.pimdir.PimdirSummary;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

import java.util.List;
import java.util.function.BiPredicate;

/**
 * The mail list under its filter and its role chips: collections by id, so
 * two accounts' mailboxes of one name are two rows, and a role chip merging
 * that role's mailbox of every shown account.
 */
@RunWith(RobolectricTestRunner.class)
public class MailFilterTest {
    private static final String JANE = "jane@example.com";
    private static final String JOHN = "john@example.org";
    private static final String DATE = "Mon, 5 Jan 2026 09:00:00 +0000";

    private Context context;
    private PimdirItems items;
    private MailStore store;

    @Before
    public void setUp() {
        context = RuntimeEnvironment.getApplication();
        PimdirDb pimdir = new PimdirDb(context);
        items = new PimdirItems(pimdir);
        store = new MailStore(context, pimdir);

        for (String account : new String[] {JANE, JOHN}) {
            store.replaceMailboxes(
                    account,
                    List.of(
                            new Mailbox("INBOX", "inbox"),
                            new Mailbox("Archive", "archive"),
                            new Mailbox("Trash", "trash")));
            envelope(account, "INBOX", "1");
            envelope(account, "Archive", "2");
            envelope(account, "Trash", "3");
        }
    }

    /** Files one envelope the way a reconcile files it. */
    private void envelope(String account, String mailbox, String id) {
        SQLiteDatabase db = items.writable();
        db.beginTransaction();
        try {
            items.put(
                    db,
                    store.collectionOf(account, mailbox),
                    new PimdirItems.Row(
                            id,
                            null,
                            PimdirSummary.mail(
                                    null, id, "Sender", "sender@example.org", null, DATE, 0,
                                    true),
                            PimdirSummary.mailSortKey(DATE),
                            "[]",
                            null));
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    private long count(BiPredicate<String, String> shown) {
        return store.count(store.query(shown, false, false, ""));
    }

    /**
     * Each account shows its inbox alone to begin with; ticking one account's
     * Archive leaves the other's hidden, and the Archive chip, a view of its
     * own, shows both.
     */
    @Test
    public void twoAccountsArchivesAreTwoCollections() {
        String hers = store.collectionOf(JANE, "Archive");
        String his = store.collectionOf(JOHN, "Archive");
        MergedFilter filter = MergedFilter.of(context, PimDomain.MAIL);

        filter.toggleCollection(hers);

        assertEquals(3, count(filter::accepts));
        assertTrue(filter.accepts(JANE, hers));
        assertFalse(filter.accepts(JOHN, his));
        BiPredicate<String, String> archives =
                MailList.narrowed(filter, "archive", store.roles())::collection;
        assertEquals(2, count(archives));
    }

    /** The Trash chip is every shown account's trash, merged, ticked or not. */
    @Test
    public void aRoleChipNarrowsToThatRoleAcrossAccounts() {
        MergedFilter filter = MergedFilter.of(context, PimDomain.MAIL);

        assertEquals(2, count(MailList.narrowed(filter, "trash", store.roles())::collection));
        assertEquals(2, count(MailList.narrowed(filter, "inbox", store.roles())::collection));
        assertEquals(0, count(MailList.narrowed(filter, "sent", store.roles())::collection));
        // NOTE: no chip on, each account shows its inbox alone by default.
        assertEquals(2, count(MailList.narrowed(filter, null, store.roles())::collection));

        // NOTE: a hidden account's trash is not one the chip shows.
        filter.toggleAccount(JOHN);
        assertEquals(1, count(MailList.narrowed(filter, "trash", store.roles())::collection));
    }

    /** The outbox is a collection of each account of its own, never a role's. */
    @Test
    public void eachOutboxIsItsAccounts() {
        List<PimdirCollections.Stored> listed = store.loadMailboxes(List.of(JANE, JOHN));
        assertEquals(store.outboxOf(JANE), listed.get(0).id);
        assertEquals(store.outboxOf(JOHN), listed.get(1).id);
        assertEquals(8, listed.size());

        BiPredicate<String, String> trash =
                MailList.narrowed(MergedFilter.of(context, PimDomain.MAIL), "trash", store.roles())
                        ::collection;
        assertFalse(trash.test(JANE, store.outboxOf(JANE)));
    }
}
