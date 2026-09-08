package org.pimalaya;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.database.Cursor;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.pimalaya.client.Message;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

import java.util.List;

/**
 * The mail spine mirror, on the shared store.
 *
 * <p>Two things are worth pinning: that a mailbox is namespaced by its account,
 * because two accounts with an INBOX is the ordinary case and a collision would
 * silently merge two people's mail, and that the merged list is ordered by the
 * key written at sync time rather than by anything parsed at render time.
 */
@RunWith(RobolectricTestRunner.class)
public class MailStoreTest {
    private static final String ONE = "jane@example.com";
    private static final String TWO = "john@example.org";

    private PimdirDb pimdir;
    private MailStore store;

    @Before
    public void setUp() {
        Context context = RuntimeEnvironment.getApplication();
        pimdir = new PimdirDb(context);
        store = new MailStore(context, pimdir);
    }

    private static Message message(String mailbox, String id, String subject, String date) {
        return new Message(
                mailbox, id, subject, "Sender", "sender@example.org", date, false, false, false,
                false);
    }

    private long scalar(String sql, String... args) {
        try (Cursor cursor = pimdir.getReadableDatabase().rawQuery(sql, args)) {
            return cursor.moveToFirst() ? cursor.getLong(0) : -1;
        }
    }

    @Test
    public void twoAccountsWithAnInboxDoNotCollide() {
        store.replaceMessages(ONE, List.of(message("INBOX", "1", "Hers", "Mon, 5 Jan 2026 09:00:00 +0000")));
        store.replaceMessages(TWO, List.of(message("INBOX", "1", "His", "Mon, 5 Jan 2026 10:00:00 +0000")));

        // Same mailbox name, same UID, different accounts: the id is namespaced
        // so these are two collections rather than one overwritten row.
        assertEquals(2, scalar("SELECT count(*) FROM collections WHERE kind = ?", PimdirSummary.MAIL));
        assertEquals(2, scalar("SELECT count(*) FROM items"));

        List<MailStore.StoredMessage> merged = store.loadMerged(10);
        assertEquals(2, merged.size());
        assertEquals("newest first", "His", merged.get(0).subject);
        assertEquals(TWO, merged.get(0).accountEmail);
        assertEquals("INBOX", merged.get(0).mailbox);
        assertEquals("1", merged.get(0).id);

        assertEquals("one name for the filter axis", List.of("INBOX"), store.loadMailboxes());
    }

    @Test
    public void theListIsOrderedByTheKeyWrittenAtSyncTime() {
        store.replaceMessages(
                ONE,
                List.of(
                        message("INBOX", "1", "Older", "Mon, 5 Jan 2026 09:00:00 +0000"),
                        message("INBOX", "2", "Newer", "Thu, 5 Feb 2026 09:00:00 +0000"),
                        message("INBOX", "3", "Undated", "not a date")));

        List<MailStore.StoredMessage> merged = store.loadMerged(10);
        assertEquals("Newer", merged.get(0).subject);
        assertEquals("Older", merged.get(1).subject);
        assertEquals("an unreadable date sinks rather than fails", "Undated", merged.get(2).subject);
        assertEquals(0, merged.get(2).stamp);

        // The label has to come from the same key the row was ordered by, or a
        // list can show dates that disagree with its own order.
        assertTrue(merged.get(0).stamp > merged.get(1).stamp);
    }

    @Test
    public void bothBackendsSpellingsOfADateOrderTogether() {
        // IMAP hands over an RFC 5322 envelope date and JMAP an RFC 3339
        // receivedAt. The merged list orders one column across both, so a
        // spelling only one of them could read would sink every message of
        // the other under every message of its neighbour.
        store.replaceMessages(
                ONE, List.of(message("INBOX", "1", "Imap", "Mon, 5 Jan 2026 09:00:00 +0000")));
        store.replaceMessages(TWO, List.of(message("INBOX", "j1", "Jmap", "2026-02-05T09:00:00Z")));

        List<MailStore.StoredMessage> merged = store.loadMerged(10);
        assertEquals("Jmap", merged.get(0).subject);
        assertEquals("j1", merged.get(0).id);
        assertTrue("an RFC 3339 date is read, not sunk", merged.get(0).stamp > 0);
        assertTrue(merged.get(0).stamp > merged.get(1).stamp);
    }

    @Test
    public void aRefreshDropsWhatTheServerNoLongerHas() {
        store.replaceMessages(
                ONE,
                List.of(
                        message("INBOX", "1", "Kept", "Mon, 5 Jan 2026 09:00:00 +0000"),
                        message("INBOX", "2", "Expunged", "Mon, 5 Jan 2026 10:00:00 +0000")));

        store.replaceMessages(ONE, List.of(message("INBOX", "1", "Kept", "Mon, 5 Jan 2026 09:00:00 +0000")));

        List<MailStore.StoredMessage> merged = store.loadMerged(10);
        assertEquals(1, merged.size());
        assertEquals("Kept", merged.get(0).subject);
    }

    @Test
    public void aSpineCarriesItsFlagsAndNoBody() {
        store.replaceMessages(ONE, List.of(flagged(true)));

        // A spine is an item with a summary and no object: hydrating it later
        // is a body write on the same row rather than a second table.
        assertEquals(0, scalar("SELECT count(*) FROM items WHERE object_hash IS NOT NULL"));
        assertTrue(store.loadMerged(10).get(0).seen);

        store.replaceMessages(ONE, List.of(flagged(false)));
        assertFalse("a flag change lands on the same row", store.loadMerged(10).get(0).seen);
    }

    /** The same message, read or unread, with every other flag set. */
    private static Message flagged(boolean seen) {
        return new Message(
                "INBOX", "7", "Read", "Ada", "a@b.c", "Mon, 5 Jan 2026 09:00:00 +0000", seen,
                true, true, true);
    }

    @Test
    public void aRowKeepsEveryFlagItsRendersOn() {
        store.replaceMessages(ONE, List.of(flagged(true)));

        // Three of the four are what the row's trailing icons read, and
        // they travel by two different routes: answered and flagged are
        // IMAP flags in the item's own flag set, the attachment is a
        // summary field, because no protocol treats it as a flag.
        MailStore.StoredMessage stored = store.loadMerged(10).get(0);
        assertTrue(stored.answered);
        assertTrue(stored.flagged);
        assertTrue(stored.hasAttachment);
    }

    @Test
    public void aSenderIsStoredByNameAndByAddress() {
        store.replaceMessages(
                ONE,
                List.of(
                        new Message(
                                "INBOX", "1", "Named", "Ada Lovelace", "ada@example.org",
                                "Mon, 5 Jan 2026 09:00:00 +0000", true, false, false, false),
                        new Message(
                                "INBOX", "2", "Nameless", "", "anon@example.org",
                                "Mon, 5 Jan 2026 10:00:00 +0000", true, false, false, false)));

        // The row shows the name and the avatar beside it is keyed by the
        // address, so losing either half would cost one of the two.
        MailStore.StoredMessage nameless = store.loadMerged(10).get(0);
        MailStore.StoredMessage named = store.loadMerged(10).get(1);

        assertEquals("Ada Lovelace", named.fromName);
        assertEquals("ada@example.org", named.fromAddress);
        assertEquals("Ada Lovelace", named.sender());
        assertEquals("a sender with no name falls back to their address",
                "anon@example.org", nameless.sender());
    }

    @Test
    public void aMarkerWrittenOnTheServerIsMirroredWithoutTouchingTheOthers() {
        store.replaceMessages(ONE, List.of(flagged(false)));

        store.setFlag(ONE, "INBOX", "7", MailStore.SEEN, true);
        MailStore.StoredMessage stored = store.loadMerged(10).get(0);
        assertTrue(stored.seen);
        assertTrue("the markers it already carried are kept", stored.flagged);
        assertTrue(stored.answered);

        store.setFlag(ONE, "INBOX", "7", MailStore.FLAGGED, false);
        stored = store.loadMerged(10).get(0);
        assertFalse(stored.flagged);
        assertTrue(stored.seen);
        assertTrue(stored.answered);
    }

    @Test
    public void aDeletedMessageLeavesTheList() {
        store.replaceMessages(
                ONE,
                List.of(
                        message("INBOX", "1", "Going", "Mon, 5 Jan 2026 09:00:00 +0000"),
                        message("INBOX", "2", "Staying", "Mon, 5 Jan 2026 10:00:00 +0000")));

        store.removeMessage(ONE, "INBOX", "1");

        List<MailStore.StoredMessage> merged = store.loadMerged(10);
        assertEquals(1, merged.size());
        assertEquals("Staying", merged.get(0).subject);
    }
}
