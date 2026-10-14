package org.pimalaya.mail;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.pimalaya.account.MergedFilter;
import org.pimalaya.account.PimDomain;
import org.pimalaya.client.Mailbox;
import org.pimalaya.pimdir.PimdirAccount;
import org.pimalaya.pimdir.PimdirCollections;
import org.pimalaya.pimdir.PimdirDb;
import org.pimalaya.pimdir.PimdirSummary;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

import java.util.List;

/**
 * What the mail filter ticks to begin with, and its page's draft.
 *
 * <p>A Gmail account every label but trash and spam, another its inbox alone,
 * an account listed before keeping its ticks; the page's ticks kept only once
 * applied.
 */
@RunWith(RobolectricTestRunner.class)
public class MailFilterDefaultsTest {
    private static final String GMAIL = "jane@gmail.com";
    private static final String IMAP = "john@example.org";

    private Context context;
    private MailStore store;
    private MergedFilter filter;

    @Before
    public void setUp() {
        context = RuntimeEnvironment.getApplication();
        store = new MailStore(context, new PimdirDb(context));
        filter = MergedFilter.of(context, PimDomain.MAIL);
    }

    private static List<Mailbox> roster() {
        return List.of(
                new Mailbox("INBOX", "inbox"),
                new Mailbox("Sent", "sent"),
                new Mailbox("Clients", ""),
                new Mailbox("Trash", "trash"),
                new Mailbox("Spam", "junk"));
    }

    @Test
    public void gmailTicksEveryLabelButItsTrashAndSpam() {
        MailPolicy.markAllMail(context, new PimdirAccount(context).idOf(GMAIL), true);
        store.replaceMailboxes(GMAIL, roster());

        assertTrue(filter.chosen(store.collectionOf(GMAIL, "INBOX")));
        assertTrue(filter.chosen(store.collectionOf(GMAIL, "Sent")));
        assertTrue(filter.chosen(store.collectionOf(GMAIL, "Clients")));
        assertFalse(filter.chosen(store.collectionOf(GMAIL, "Trash")));
        assertFalse(filter.chosen(store.collectionOf(GMAIL, "Spam")));
    }

    @Test
    public void anyOtherAccountTicksItsInboxAlone() {
        store.replaceMailboxes(IMAP, roster());

        assertTrue(filter.chosen(store.collectionOf(IMAP, "INBOX")));
        assertFalse(filter.chosen(store.collectionOf(IMAP, "Sent")));
        assertFalse(filter.chosen(store.collectionOf(IMAP, "Clients")));
        assertFalse(filter.chosen(store.collectionOf(IMAP, "Trash")));
        assertFalse(filter.isActive());

        // NOTE: a mailbox ticked stays so through the next roster, and one
        // the server adds later starts unticked.
        filter.toggleCollection(store.collectionOf(IMAP, "Sent"));
        store.replaceMailboxes(
                IMAP,
                List.of(
                        new Mailbox("INBOX", "inbox"),
                        new Mailbox("Sent", "sent"),
                        new Mailbox("Projects", "")));
        assertTrue(filter.chosen(store.collectionOf(IMAP, "Sent")));
        assertFalse(filter.chosen(store.collectionOf(IMAP, "Projects")));
    }

    @Test
    public void anAccountListedBeforeKeepsItsTicks() {
        // NOTE: listed before inbox-only existed: its roster is stored, its
        // account never marked.
        new PimdirCollections(new PimdirDb(context), context)
                .replace(
                        IMAP,
                        PimdirSummary.MAIL,
                        MailEngine.collectionsOf(
                                IMAP,
                                new PimdirAccount(context).idOf(IMAP),
                                List.of(new Mailbox("INBOX", "inbox"))));
        store.replaceMailboxes(IMAP, roster());

        assertTrue(filter.chosen(store.collectionOf(IMAP, "Sent")));
        assertFalse(filter.chosen(store.collectionOf(IMAP, "Trash")));
    }

    @Test
    public void thePagesTicksAreKeptOnlyOnceApplied() {
        store.replaceMailboxes(IMAP, roster());
        String sent = store.collectionOf(IMAP, "Sent");
        MergedFilter draft = filter.draft();

        draft.toggleCollection(sent);
        assertTrue(draft.chosen(sent));
        assertFalse("not kept before Apply", filter.chosen(sent));
        assertFalse(MergedFilter.of(context, PimDomain.MAIL).chosen(sent));

        filter.apply(draft);
        assertTrue(filter.chosen(sent));
        assertFalse("showing more hides nothing", filter.isActive());
    }
}
