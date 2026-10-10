package org.pimalaya;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import org.json.JSONArray;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.pimalaya.client.Mailbox;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * The mail side of the store, on the shared pimdir store.
 *
 * <p>Three things are worth pinning: that a mailbox is namespaced by its
 * account, because two accounts with an INBOX is the ordinary case and a
 * collision would silently merge two people's mail; that the merged list is
 * ordered by the key written at sync time rather than by anything parsed at
 * render time; and that the outbox survives a roster replace, since it holds
 * the one thing in the store no server could hand back.
 *
 * <p>The items are written here the way the engine writes them, through
 * {@link PimdirItems}. What the engine does on top of that (the reconcile, the
 * staged writes it derives) is the engine's to pin.
 */
@RunWith(RobolectricTestRunner.class)
public class MailStoreTest {
    private static final String ONE = "jane@example.com";
    private static final String TWO = "john@example.org";
    private static final String DATE = "Mon, 5 Jan 2026 09:00:00 +0000";
    private static final String SOURCE = "From: a@example.org\r\n\r\nthe body\r\n";

    private PimdirDb pimdir;
    private PimdirItems items;
    private MailStore store;

    @Before
    public void setUp() {
        Context context = RuntimeEnvironment.getApplication();
        pimdir = new PimdirDb(context);
        items = new PimdirItems(pimdir);
        store = new MailStore(context, pimdir);
    }

    /** Lists one account's mailboxes, none of them a trash. */
    private void mailboxes(String accountEmail, String... names) {
        List<Mailbox> listed = new java.util.ArrayList<>(names.length);
        for (String name : names) {
            listed.add(new Mailbox(name, ""));
        }
        store.replaceMailboxes(accountEmail, listed);
    }

    /** Files one envelope the way a reconcile files it: a summary, no body. */
    private void envelope(
            String accountEmail,
            String mailbox,
            String id,
            String subject,
            String date,
            String... flags) {
        String collection = store.collectionOf(accountEmail, mailbox);
        JSONArray marks = new JSONArray();
        for (String flag : flags) {
            marks.put(flag);
        }

        SQLiteDatabase db = items.writable();
        db.beginTransaction();
        try {
            items.put(
                    db,
                    collection,
                    new PimdirItems.Row(
                            id,
                            null,
                            PimdirSummary.mail(
                                    null, subject, "Sender", "sender@example.org", null, date, 0,
                                    true),
                            PimdirSummary.mailSortKey(date),
                            marks.toString(),
                            null));
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    private long scalar(String sql, String... args) {
        try (Cursor cursor = pimdir.getReadableDatabase().rawQuery(sql, args)) {
            return cursor.moveToFirst() ? cursor.getLong(0) : -1;
        }
    }

    @Test
    public void twoAccountsWithAnInboxDoNotCollide() {
        mailboxes(ONE, "INBOX");
        mailboxes(TWO, "INBOX");
        envelope(ONE, "INBOX", "1", "Hers", "Mon, 5 Jan 2026 09:00:00 +0000");
        envelope(TWO, "INBOX", "1", "His", "Mon, 5 Jan 2026 10:00:00 +0000");

        // Same mailbox name, same UID, different accounts: the id is namespaced
        // so these are two collections rather than one overwritten row.
        assertEquals(2, scalar("SELECT count(*) FROM items"));

        List<MailStore.StoredMessage> merged = store.loadMerged(10);
        assertEquals(2, merged.size());
        assertEquals("newest first", "His", merged.get(0).subject);
        assertEquals(TWO, merged.get(0).accountEmail);
        assertEquals("INBOX", merged.get(0).mailbox);
        assertEquals("1", merged.get(0).id);
    }

    @Test
    public void theListIsOrderedByTheKeyWrittenAtSyncTime() {
        mailboxes(ONE, "INBOX");
        envelope(ONE, "INBOX", "1", "Older", "Mon, 5 Jan 2026 09:00:00 +0000");
        envelope(ONE, "INBOX", "2", "Newer", "Thu, 5 Feb 2026 09:00:00 +0000");
        envelope(ONE, "INBOX", "3", "Undated", "not a date");

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
        mailboxes(ONE, "INBOX");
        mailboxes(TWO, "INBOX");
        envelope(ONE, "INBOX", "1", "Imap", "Mon, 5 Jan 2026 09:00:00 +0000");
        envelope(TWO, "INBOX", "j1", "Jmap", "2026-02-05T09:00:00Z");

        List<MailStore.StoredMessage> merged = store.loadMerged(10);
        assertEquals("Jmap", merged.get(0).subject);
        assertEquals("j1", merged.get(0).id);
        assertTrue("an RFC 3339 date is read, not sunk", merged.get(0).stamp > 0);
        assertTrue(merged.get(0).stamp > merged.get(1).stamp);
    }

    @Test
    public void aRowCarriesEveryFlagItRendersOn() {
        mailboxes(ONE, "INBOX");
        envelope(
                ONE, "INBOX", "7", "Read", DATE, MailEngine.SEEN, MailEngine.ANSWERED,
                MailEngine.FLAGGED);

        // Three of the four are what the row's trailing icons read, and they
        // travel by two different routes: answered and flagged are IMAP flags
        // in the item's own flag set, the attachment is a summary field,
        // because no protocol treats it as a flag.
        MailStore.StoredMessage stored = store.loadMerged(10).get(0);
        assertTrue(stored.seen);
        assertTrue(stored.answered);
        assertTrue(stored.flagged);
        assertTrue(stored.hasAttachment);
        assertFalse("a synced message is not pending", stored.pending);
    }

    @Test
    public void aMarkerSetIsReadBackWhole() {
        mailboxes(ONE, "INBOX");
        // A keyword this app does not model, which a toggle has to leave alone:
        // it is read back so the staged set can carry it through unchanged.
        envelope(ONE, "INBOX", "7", "Read", DATE, MailEngine.SEEN, "$junk");

        JSONArray flags = store.flagsOf(store.collectionOf(ONE, "INBOX"), "7");
        assertEquals(2, flags.length());
        assertTrue(MailEngine.has(flags, MailEngine.SEEN));
        assertTrue(MailEngine.has(flags, "$junk"));

        JSONArray moved = MailEngine.withFlag(flags, MailEngine.FLAGGED, true);
        assertTrue("the marker the reader moved", MailEngine.has(moved, MailEngine.FLAGGED));
        assertTrue("and the one it never modelled", MailEngine.has(moved, "$junk"));
    }

    @Test
    public void aSenderIsStoredByNameAndByAddress() {
        mailboxes(ONE, "INBOX");
        String collection = store.collectionOf(ONE, "INBOX");

        SQLiteDatabase db = items.writable();
        db.beginTransaction();
        try {
            items.put(
                    db,
                    collection,
                    new PimdirItems.Row(
                            "1",
                            null,
                            PimdirSummary.mail(
                                    null, "Named", "Ada Lovelace", "ada@example.org", null, DATE, 0,
                                    false),
                            PimdirSummary.mailSortKey(DATE),
                            "[]",
                            null));
            items.put(
                    db,
                    collection,
                    new PimdirItems.Row(
                            "2",
                            null,
                            PimdirSummary.mail(
                                    null, "Nameless", "", "anon@example.org", null,
                                    "Mon, 5 Jan 2026 10:00:00 +0000", 0, false),
                            PimdirSummary.mailSortKey("Mon, 5 Jan 2026 10:00:00 +0000"),
                            "[]",
                            null));
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }

        // The row shows the name and the avatar beside it is keyed by the
        // address, so losing either half would cost one of the two.
        MailStore.StoredMessage nameless = store.loadMerged(10).get(0);
        MailStore.StoredMessage named = store.loadMerged(10).get(1);

        assertEquals("Ada Lovelace", named.fromName);
        assertEquals("ada@example.org", named.fromAddress);
        assertEquals("Ada Lovelace", named.sender());
        assertEquals(
                "a sender with no name falls back to their address",
                "anon@example.org",
                nameless.sender());
    }

    @Test
    public void anEnvelopeCarriesNoBodyUntilItIsOpened() {
        mailboxes(ONE, "INBOX");
        envelope(ONE, "INBOX", "1", "Unread", DATE);

        assertNull(store.source(store.loadMerged(10).get(0)));
        assertEquals(PimdirItems.META, scalar("SELECT level FROM items WHERE link_id = ?", "1"));
    }

    @Test
    public void anOpenedMessageIsReadBackAsTheServerSentIt() {
        mailboxes(ONE, "INBOX");
        envelope(ONE, "INBOX", "1", "Read", DATE);

        String collection = store.collectionOf(ONE, "INBOX");
        store.saveSource(collection, "1", SOURCE.getBytes(StandardCharsets.UTF_8));

        byte[] stored = store.source(store.loadMerged(10).get(0));
        assertEquals(SOURCE, new String(stored, StandardCharsets.UTF_8));
        assertEquals(PimdirItems.FULL, scalar("SELECT level FROM items WHERE link_id = ?", "1"));
    }

    @Test
    public void aQueuedMessageSurvivesARosterReplaceAndTheTrashIsRemembered() {
        mailboxes(ONE, "INBOX");
        String outbox = store.outboxOf(ONE);

        store.queueSubmission(
                ONE,
                "queued@example.org",
                "Waiting",
                DATE,
                SOURCE.getBytes(StandardCharsets.UTF_8));

        // A roster replace drops every mail collection the account no longer
        // lists, and no server lists an outbox: taking its silence for a
        // removal would lose a message nothing could hand back. The queue
        // rows are the ones a replace never looks at.
        store.replaceMailboxes(
                ONE, List.of(new Mailbox("INBOX", ""), new Mailbox("Bin", Mailbox.TRASH)));

        List<MailStore.Outgoing> waiting = store.outgoing(ONE);
        assertEquals(1, waiting.size());
        assertEquals(SOURCE, new String(waiting.get(0).source, StandardCharsets.UTF_8));

        assertEquals("read back with no network to ask", "Bin", store.trashOf(ONE));

        // And it reads as pending, which is the one state where the store
        // holds a message no server has.
        MailStore.StoredMessage row = store.loadMerged(10).get(0);
        assertTrue(row.pending);
        assertFalse(row.failed);
        assertEquals("Waiting", row.subject);
        assertEquals(outbox, row.collection);
        assertEquals(SOURCE, new String(store.source(row), StandardCharsets.UTF_8));

        store.acknowledge(waiting.get(0).id);
        assertTrue(store.outgoing(ONE).isEmpty());
        assertEquals("the body goes with it", 0, scalar("SELECT count(*) FROM objects"));
    }

    @Test
    public void aParkedSubmissionLeavesTheDrainAndStaysVisible() {
        mailboxes(ONE, "INBOX");
        store.queueSubmission(
                ONE,
                "refused@example.org",
                "Refused",
                DATE,
                SOURCE.getBytes(StandardCharsets.UTF_8));

        long queued = store.outgoing(ONE).get(0).id;
        store.parkOutgoing(queued, "550 no such recipient");

        assertTrue("nothing offers it to the server again", store.outgoing(ONE).isEmpty());

        // Still a message the sender wrote, so it is still shown, saying
        // that it was refused rather than disappearing.
        MailStore.StoredMessage row = store.loadMerged(10).get(0);
        assertTrue(row.failed);
        assertTrue(row.pending);
        assertEquals(SOURCE, new String(store.source(row), StandardCharsets.UTF_8));
    }

    @Test
    public void anActionOfAnotherKindIsLeftAlone() {
        mailboxes(ONE, "INBOX");
        store.queueSubmission(
                ONE, "mine@example.org", "Mine", DATE, SOURCE.getBytes(StandardCharsets.UTF_8));
        queueForeign(store.outboxOf(ONE));

        // Skipped rather than parked or applied: an owner that does not
        // recognise a kind leaves the row exactly as it found it and never
        // blocks the ones behind it (STORAGE section 15.2).
        assertEquals(1, store.outgoing(ONE).size());
        assertEquals(
                "the foreign row is untouched",
                2,
                scalar("SELECT count(*) FROM queue WHERE error IS NULL"));
    }

    /** Appends one action of a kind this app does not carry out. */
    private void queueForeign(String collection) {
        pimdir.getWritableDatabase()
                .execSQL(
                        "INSERT INTO queue(created_at, producer, collection, action, payload)"
                                + " VALUES(?, ?, ?, ?, ?)",
                        new Object[] {DATE, "someone.else", collection, "set-flags", "{}"});
    }

    @Test
    public void anOutboxWrittenAsItemsMovesOntoTheQueue() {
        mailboxes(ONE, "INBOX");
        String outbox = store.outboxOf(ONE);

        // The shape the version before this one wrote: an item in a mail
        // collection of its own, which nothing drains any more and which
        // the next roster replace would cascade away.
        new PimdirCollections(pimdir, RuntimeEnvironment.getApplication())
                .ensure(outbox, ONE, PimdirSummary.MAIL, "Outbox");

        SQLiteDatabase db = items.writable();
        db.beginTransaction();
        try {
            items.put(
                    db,
                    outbox,
                    new PimdirItems.Row(
                            "stranded@example.org",
                            SOURCE,
                            PimdirSummary.mail(
                                    null, "Waiting", "", ONE, null, DATE, SOURCE.length(), false),
                            PimdirSummary.mailSortKey(DATE),
                            "[]",
                            null));
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }

        List<MailStore.Outgoing> waiting = store.outgoing(ONE);
        assertEquals(1, waiting.size());
        assertEquals(SOURCE, new String(waiting.get(0).source, StandardCharsets.UTF_8));

        assertEquals("the item is gone", 0, scalar("SELECT count(*) FROM items"));
        assertEquals("and it is on the queue", 1, store.loadMerged(10).size());

        MailStore.StoredMessage row = store.loadMerged(10).get(0);
        assertTrue(row.pending);
        assertEquals("Waiting", row.subject);

        // Twice is the same as once: nothing is left to move.
        assertEquals(1, store.outgoing(ONE).size());
    }

    @Test
    public void anAccountMarkingNoTrashRemembersNone() {
        mailboxes(ONE, "INBOX", "Archive");
        assertEquals("", store.trashOf(ONE));
    }

    /** Files one message the way a listing names it: bound, agreed, no body. */
    private void listed(
            String accountEmail, String id, String subject, String date, boolean attachment,
            String... flags) throws Exception {
        sized(accountEmail, id, subject, date, 0, attachment, flags);
    }

    /** {@link #listed}, weighing {@code size} bytes (0 for unknown). */
    private void sized(
            String accountEmail, String id, String subject, String date, long size,
            boolean attachment, String... flags) throws Exception {
        String collection = store.collectionOf(accountEmail, "INBOX");
        JSONArray marks = new JSONArray();
        for (String flag : flags) {
            marks.put(flag);
        }
        org.json.JSONObject placement = new org.json.JSONObject();
        placement.put("collection", collection);
        placement.put("handle", id);
        placement.put("linkId", id);
        placement.put("level", "meta");
        placement.put("status", "clean");
        placement.put("flags", marks);
        placement.put(
                "summary",
                PimdirSummary.mail(
                        null, subject, "Sender", "sender@example.org", null, date, size,
                        attachment));
        placement.put("sortKey", PimdirSummary.mailSortKey(date));
        placement.put("base", new org.json.JSONObject().put("flags", marks));
        new PimdirStorage(pimdir)
                .applyWrites(
                        new JSONArray()
                                .put(
                                        new org.json.JSONObject()
                                                .put("op", "upsert")
                                                .put("placement", placement)));
    }

    /** The whole store's mail, chips as given. */
    private MailStore.Query all(boolean unread, boolean attachments, String words) {
        return store.query((account, mailbox) -> true, unread, attachments, words);
    }

    @Test
    public void theListIsSizedPlacedAndPagedByTheStore() throws Exception {
        mailboxes(ONE, "INBOX");
        // 120 messages over 60 days, every third one unread, every fourth
        // one carrying an attachment.
        for (int index = 0; index < 120; index++) {
            String date =
                    java.time.LocalDate.of(2026, 1, 1).plusDays(index / 2)
                            + (index % 2 == 0 ? "T09:00:00Z" : "T15:00:00Z");
            listed(
                    ONE, String.valueOf(index), "Message " + index, date, index % 4 == 0,
                    index % 3 == 0 ? new String[0] : new String[] {MailEngine.SEEN});
        }

        MailStore.Query query = all(false, false, "");
        assertEquals(120, store.count(query));
        long placed = 0;
        for (MailStore.Day day : store.countByDay(query, "+0 minutes")) {
            assertTrue("a day header counts no more than its day", day.count <= 2);
            placed += day.count;
        }
        assertEquals("the day headers place every row", 120, placed);

        // A page read after the one before it (a scroll) and one read from
        // its offset (a fling) are the same rows.
        List<MailStore.StoredMessage> first = store.page(query, null, 0, 50);
        List<MailStore.StoredMessage> scrolled = store.page(query, first.get(49), 0, 50);
        List<MailStore.StoredMessage> flung = store.page(query, null, 50, 50);
        assertEquals(50, scrolled.size());
        for (int index = 0; index < 50; index++) {
            assertEquals(scrolled.get(index).id, flung.get(index).id);
        }
        assertTrue("newest first", first.get(0).stamp >= first.get(49).stamp);
        assertEquals(20, store.page(query, null, 100, 50).size());

        // The chips and the badge are conditions of the query, over every
        // stored message rather than over what a page holds.
        assertEquals(40, store.count(all(true, false, "")));
        assertEquals(40, store.count(query.unread()));
        assertEquals(30, store.count(all(false, true, "")));
        assertEquals(40, store.unread(query));
        assertEquals(10, store.count(all(true, true, "")));

        // A floor is the statements' own: the count, the days, the pages and
        // the badge stop at it. February on holds rows 62 to 119.
        MailStore.Query floored = query.since("2026-02-01T00:00:00Z");
        assertEquals(58, store.count(floored));
        long days = 0;
        for (MailStore.Day day : store.countByDay(floored, "+0 minutes")) {
            days += day.count;
        }
        assertEquals(58, days);
        assertEquals(58, store.all(floored).size());
        assertEquals("unread below the floor is not counted", 19, store.unread(floored));
        assertEquals(19, store.count(floored.unread()));

        // What a range weighs, sizes the store does not know told apart.
        sized(ONE, "s1", "Sized", "2026-03-02T09:00:00Z", 1_000, false);
        sized(ONE, "s2", "Sized", "2026-03-02T10:00:00Z", 2_000, false);
        MailStore.Sum known = store.sum(query, "2026-03-02T00:00:00Z", null);
        assertEquals(2, known.count);
        assertEquals(3_000, known.size);
        assertEquals(0, known.unknown);
        MailStore.Sum unknown = store.sum(query, "2026-02-01T00:00:00Z", "2026-03-02T00:00:00Z");
        assertEquals(58, unknown.count);
        assertEquals(58, unknown.unknown);
        assertTrue(
                "the newest below a floor",
                store.newestBelow(query, "2026-03-02T00:00:00Z").startsWith("2026-03-01"));
        assertNull(store.newestBelow(query, "2026-01-01T00:00:00Z"));
    }

    @Test
    public void searchReachesTheOldestStoredMessage() throws Exception {
        mailboxes(ONE, "INBOX");
        listed(ONE, "old", "Invoice 2023", "2023-03-01T08:00:00Z", false);
        for (int index = 0; index < 60; index++) {
            listed(ONE, "n" + index, "Newsletter " + index, "2026-09-01T08:00:00Z", false);
        }

        MailStore.Query found = all(false, false, "invoice");
        assertEquals(1, store.count(found));
        assertEquals("old", store.page(found, null, 0, 50).get(0).id);
        assertEquals(1, store.countByDay(found, "+0 minutes").size());
        // A literal wildcard is searched for, not matched by.
        assertEquals(0, store.count(all(false, false, "100%")));
        assertEquals("%100\\%%", MailStore.likePattern("100%"));
    }

    @Test
    public void aNarrowedBoundCollectsWhatFallsBelowIt() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        mailboxes(ONE, "INBOX");
        listed(ONE, "old", "Old", "2020-01-01T08:00:00Z", false);
        listed(ONE, "kept", "Old but starred here", "2020-01-02T08:00:00Z", false);
        listed(ONE, "new", "New", "2099-01-01T08:00:00Z", false);
        listed(ONE, "undated", "Undated", "not a date", false);
        String accountId = store.accountIdOf(ONE);
        MailWindow.set(context, accountId, "2019-06-01T00:00:00Z");

        // A flag change staged and not pushed yet owes the server something,
        // so a collection leaves it.
        MailEngine engine =
                new MailEngine(
                        pimdir, new org.pimalaya.client.PimalayaClient(), null, accountId);
        String collection = store.collectionOf(ONE, "INBOX");
        engine.mutateFlags(collection, "kept", new JSONArray().put(MailEngine.FLAGGED));

        assertEquals(1, store.bound(ONE, 6));
        assertEquals(6, store.monthsOf(ONE));
        assertEquals(3, store.count(all(false, false, "")));
        assertEquals(0, scalar("SELECT count(*) FROM items WHERE link_id = 'old'"));
        assertEquals("an undated message is never older", 1,
                scalar("SELECT count(*) FROM items WHERE link_id = 'undated'"));
        String floor = MailScope.since(6, java.time.LocalDate.now(java.time.ZoneOffset.UTC));
        assertEquals("the window is raised to the floor", floor, store.windowOf(ONE));

        assertEquals("a wider bound collects nothing", 0, store.bound(ONE, 0));
        assertEquals(0, store.monthsOf(ONE));
        assertEquals("and leaves the window where it was raised", floor, store.windowOf(ONE));
    }

    @Test
    public void aBoundIsTheFirstOfTheMonthItReachesBack() {
        java.time.LocalDate today = java.time.LocalDate.of(2026, 10, 7);
        assertEquals("2026-04-01T00:00:00Z", MailScope.since(6, today));
        assertEquals("2025-10-01T00:00:00Z", MailScope.since(12, today));
        assertNull("all mail has no floor", MailScope.since(0, today));
    }

    @Test
    public void anAccountSetUpBeforeWindowsTakesOne() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        store.replaceMailboxes(ONE, List.of(new Mailbox("INBOX", "inbox")));
        java.time.Instant start = java.time.Instant.parse("2026-09-01T00:00:00Z");
        for (int index = 0; index < 60; index++) {
            listed(ONE, "m" + index, "M", start.plusSeconds(index * 3600L).toString(), false);
        }
        String accountId = store.accountIdOf(ONE);

        // NOTE: bodies on open: the inbox's first chunk as stored, the
        // date of its 50th newest message, so nothing downloads by surprise.
        MailWindow.migrate(context, store, ONE);
        assertTrue(store.windowOf(ONE).startsWith("2026-09-01T10:00"));

        // NOTE: an account that held its bodies takes its bound's floor,
        // and the old policy is forgotten.
        MailWindow.forget(context, accountId);
        context.getSharedPreferences("mail-offline", Context.MODE_PRIVATE)
                .edit()
                .putInt("policy:" + accountId, 1)
                .apply();
        MailScope.set(context, accountId, 6);
        MailWindow.migrate(context, store, ONE);
        assertEquals(
                MailScope.since(6, java.time.LocalDate.now(java.time.ZoneOffset.UTC)),
                store.windowOf(ONE));
        assertFalse(MailOffline.downloadedAhead(context, accountId));

        // NOTE: once held, a window is never migrated again.
        MailScope.set(context, accountId, 0);
        MailWindow.migrate(context, store, ONE);
        assertEquals(
                MailScope.since(6, java.time.LocalDate.now(java.time.ZoneOffset.UTC)),
                store.windowOf(ONE));
    }
}
