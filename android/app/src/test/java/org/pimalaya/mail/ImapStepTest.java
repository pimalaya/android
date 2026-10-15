package org.pimalaya.mail;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.database.Cursor;

import io.requery.android.database.sqlite.SQLiteDatabase;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.pimalaya.client.AccountClient;
import org.pimalaya.client.CalendarClient;
import org.pimalaya.client.Clients;
import org.pimalaya.client.ContactClient;
import org.pimalaya.client.FloorReply;
import org.pimalaya.client.MailClient;
import org.pimalaya.client.MailPage;
import org.pimalaya.client.MailRequest;
import org.pimalaya.client.MailSession;
import org.pimalaya.client.MailWire;
import org.pimalaya.client.Mailbox;
import org.pimalaya.client.MessageSource;
import org.pimalaya.client.MessageSources;
import org.pimalaya.client.OfflineClient;
import org.pimalaya.client.PimalayaException;
import org.pimalaya.pimdir.PimdirAccount;
import org.pimalaya.pimdir.PimdirDb;
import org.pimalaya.pimdir.PimdirSummary;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * An IMAP step from the engine down to the store.
 *
 * <p>The first takes the inbox's floor, lists every mailbox from it and reads
 * the bodies above it, the junk's left out; a later one widens from the store
 * and reads the bodies between the new frontier and the old.
 */
@RunWith(RobolectricTestRunner.class)
public class ImapStepTest {
    private static final String EMAIL = "jane@example.com";
    private static final Instant TOP = Instant.parse("2026-10-01T00:00:00Z");

    private Context context;
    private PimdirDb pimdir;
    private MailStore store;
    private String accountId;
    private String inbox;
    private String sent;
    private String junk;

    @Before
    public void setUp() {
        context = RuntimeEnvironment.getApplication();
        pimdir = new PimdirDb(context);
        store = new MailStore(context, pimdir);
        store.replaceMailboxes(
                EMAIL,
                List.of(
                        new Mailbox("INBOX", "inbox"),
                        new Mailbox("Sent", "sent"),
                        new Mailbox("Junk", "junk")));
        accountId = new PimdirAccount(context).idOf(EMAIL);
        inbox = store.collectionOf(EMAIL, "INBOX");
        sent = store.collectionOf(EMAIL, "Sent");
        junk = store.collectionOf(EMAIL, "Junk");
    }

    /**
     * An account whose mailboxes each hold {@code size} dated messages.
     *
     * <p>UIDs 1 to {@code size}, newest on top, each {@code spacing} hours
     * older than the one above; each mailbox 20 minutes below the one declared
     * before it, so no two messages share a date.
     */
    private final class Server extends MailClient {
        final Map<String, int[]> shapes = new HashMap<>();
        final List<String> read = new ArrayList<>();

        /** Mailboxes the server refuses to open, and how. */
        final Map<String, Integer> refused = new HashMap<>();

        /** The account's driver, on this server. */
        final MailEngine engine =
                new MailEngine(
                        pimdir,
                        new Clients(
                                new AccountClient(), new ContactClient(), new CalendarClient(),
                                this, new OfflineClient()),
                        null,
                        accountId);

        Server mailbox(String collection, int size, int spacing) {
            shapes.put(collection, new int[] {size, spacing, shapes.size() * 20});
            return this;
        }

        /**
         * The EXAMINE a read opens a mailbox with.
         *
         * <p>Refused with a 404 as the bridge answers Gmail's {@code NO Unknown
         * Mailbox}, or failing with no status as a dropped connection does.
         */
        void open(String collection) {
            if (refused.containsKey(collection)) {
                throw new PimalayaException(
                        "IMAP EXAMINE failed: NO Unknown Mailbox: " + collection + " (Failure)",
                        refused.get(collection));
            }
        }

        String dateOf(String collection, int uid) {
            int[] shape = shapes.get(collection);
            return TOP.minusSeconds((long) (shape[0] - uid) * shape[1] * 3600 + shape[2] * 60L)
                    .toString();
        }

        @Override
        public FloorReply floor(MailSession session, String mailbox, String before, int count) {
            String collection = store.collectionOf(EMAIL, mailbox);
            open(collection);
            List<String> dates = new ArrayList<>();
            for (int uid = shapes.get(collection)[0]; uid >= 1 && dates.size() < count; uid--) {
                String date = dateOf(collection, uid);
                if (before == null || date.compareTo(before) < 0) {
                    dates.add(date);
                }
            }
            String floor = dates.size() < count ? null : dates.get(dates.size() - 1);
            return new FloorReply(floor, dates.size(), dates);
        }

        @Override
        public MailPage enumerateMailbox(MailSession session, String mailbox, MailRequest request) {
            String collection = store.collectionOf(EMAIL, mailbox);
            open(collection);
            try {
                MailRequest.Listing listing = request.listing();
                String since = request.scope().since();
                String until = request.scope().until();
                JSONObject page =
                        new JSONObject().put("vanished", new JSONArray()).put("last", true);
                if (!listing.round()) {
                    return MailWire.page(page.put("items", new JSONArray()).put("complete", false)
                            .put("checkpoint", "cp"));
                }
                JSONArray items = new JSONArray();
                for (int uid = shapes.get(collection)[0]; uid >= 1; uid--) {
                    String date = dateOf(collection, uid);
                    if ((since == null || date.compareTo(since) >= 0)
                            && (until == null || date.compareTo(until) < 0)) {
                        items.put(named(String.valueOf(uid), date));
                    }
                }
                page.put("items", items).put("complete", true);
                if (!listing.band()) {
                    page.put("checkpoint", "cp");
                }
                return MailWire.page(page);
            } catch (JSONException failure) {
                throw new AssertionError(failure);
            }
        }

        @Override
        public MessageSources fetchSources(
                MailSession session, String mailbox, List<String> ids, File dir) {
            String collection = store.collectionOf(EMAIL, mailbox);
            List<MessageSource> items = new ArrayList<>();
            for (String handle : ids) {
                read.add(collection + "/" + handle);
                File file = new File(dir, handle + ".eml");
                try {
                    Files.write(file.toPath(), source(collection, handle));
                } catch (IOException failure) {
                    throw new AssertionError(failure);
                }
                items.add(
                        new MessageSource(
                                handle, null, file.getAbsolutePath(), null, file.length()));
            }
            return new MessageSources(List.of(), items);
        }

        String body(String collection, String handle) {
            return "Body " + handle + " of " + collection;
        }

        byte[] source(String collection, String handle) {
            return ("Date: " + dateOf(collection, Integer.parseInt(handle))
                            + "\r\nSubject: Subject " + handle + "\r\n\r\n" + body(collection, handle)
                            + "\r\n")
                    .getBytes(StandardCharsets.UTF_8);
        }
    }

    private static JSONObject named(String handle, String date) throws JSONException {
        return new JSONObject()
                .put("handle", handle)
                .put("flags", new JSONArray())
                .put("linkId", handle)
                .put(
                        "summary",
                        PimdirSummary.mail(
                                null, "Subject " + handle, "", "a@example.org", null, date, 0,
                                false))
                .put("sortKey", PimdirSummary.mailSortKey(date));
    }

    private List<String> collections() {
        return List.of(inbox, sent, junk);
    }

    /** How many messages a mailbox holds, and how many of them whole. */
    private int[] held(String collection) {
        int all = 0;
        int whole = 0;
        for (MailBodies.Row row : store.bodyRows(List.of(collection), null)) {
            all++;
            whole += row.bodied ? 1 : 0;
        }
        return new int[] {all, whole};
    }

    @Test
    public void aFirstStepListsEveryMailboxFromTheAccountFloorAndReadsItsBodies()
            throws Exception {
        Server server = new Server().mailbox(inbox, 120, 1).mailbox(sent, 30, 3).mailbox(junk, 10, 1);

        MailStep.Outcome first =
                new ImapStep().run(server.engine, new StepRun(EMAIL, collections(), null, true));

        // NOTE: every three hours three inbox messages and one sent: the
        // account's 50th newest is the sent mail 36 hours 20 minutes back.
        assertEquals(
                "the 50th newest by date across the inbox and the sent mail",
                server.dateOf(sent, 18),
                first.frontier);
        assertFalse(first.exhausted);
        assertEquals(37, held(inbox)[0]);
        assertEquals(37, held(inbox)[1]);
        assertEquals(13, held(sent)[0]);
        assertEquals(13, held(sent)[1]);
        assertEquals("the junk is listed for its checkpoint", 10, held(junk)[0]);
        assertEquals("its bodies are not read", 0, held(junk)[1]);
        assertEquals("about a step's worth", 50, first.brought);
        assertEquals(50, server.read.size());
        for (String collection : collections()) {
            assertNotNull(collection + " opened", store.coverage(collection).at);
        }
        assertEquals(server.body(inbox, "120"), bodyOf(inbox, "120"));
    }

    @Test
    public void twoMailboxesSharingUidsKeepTheirOwnBodies() throws Exception {
        // NOTE: UIDs 1 to 120 in both, so the first step reads the same
        // UIDs in each: a UID names a message in its own mailbox alone.
        Server server = new Server().mailbox(inbox, 120, 1).mailbox(sent, 120, 1).mailbox(junk, 0, 1);

        MailStep.Outcome first =
                new ImapStep().run(server.engine, new StepRun(EMAIL, collections(), null, true));

        assertEquals(50, first.brought);
        assertEquals(25, held(inbox)[1]);
        assertEquals(25, held(sent)[1]);
        for (int uid = 120; uid > 95; uid--) {
            String handle = String.valueOf(uid);
            assertEquals(server.body(inbox, handle), bodyOf(inbox, handle));
            assertEquals(server.body(sent, handle), bodyOf(sent, handle));
            assertTrue("read, not linked: " + handle, server.read.contains(sent + "/" + handle));
        }
    }

    @Test
    public void bodiesOnceLinkedAcrossMailboxesAreReleasedOnceAndReadAgain() throws Exception {
        Server server = new Server().mailbox(inbox, 120, 1).mailbox(sent, 120, 1).mailbox(junk, 0, 1);
        new ImapStep().run(server.engine, new StepRun(EMAIL, collections(), null, true));

        crossLink("120");
        assertEquals(server.body(inbox, "120"), bodyOf(sent, "120"));

        assertEquals("both sides of the link", 2, store.repairLinks(accountId));
        assertEquals("once", 0, store.repairLinks(accountId));
        assertEquals(24, held(inbox)[1]);
        assertEquals(24, held(sent)[1]);

        // NOTE: the frontier at all mail, so the download takes both bodies
        // whatever today's date and zone: with none held, its window is the
        // first of the current month, local midnight, which the sent copy
        // (twenty minutes before the inbox's, at the turn of a month) falls
        // below in UTC.
        MailWindow.set(context, accountId, null);
        server.read.clear();
        for (String collection : List.of(inbox, sent)) {
            List<MailBodies.Row> rows = new ArrayList<>();
            for (MailBodies.Row row : store.bodyRows(List.of(collection), null)) {
                if (!row.bodied && row.linkId.equals("120")) {
                    rows.add(row);
                }
            }
            server.engine.download(collection, rows);
        }
        assertEquals(List.of(inbox + "/120", sent + "/120"), server.read);
        assertEquals(server.body(inbox, "120"), bodyOf(inbox, "120"));
        assertEquals(server.body(sent, "120"), bodyOf(sent, "120"));
    }

    @Test
    public void theRepairKeepsABodyItsMessageStillNeeds() throws Exception {
        Server server = new Server().mailbox(inbox, 120, 1).mailbox(sent, 120, 1).mailbox(junk, 0, 1);
        new ImapStep().run(server.engine, new StepRun(EMAIL, collections(), null, true));
        String own = hashOf(sent, "120");
        for (String handle : List.of("117", "118", "119", "120")) {
            crossLink(handle);
        }

        // NOTE: the sent copies of 118 to 120 need their body as
        // release_before says: a conflict, a pending create (no base), a
        // local edit (a base naming another body).
        SQLiteDatabase db = pimdir.getWritableDatabase();
        db.execSQL(
                "UPDATE bindings SET conflicted = 1 WHERE collection = ? AND link_id = '118'",
                new Object[] {sent});
        db.execSQL(
                "UPDATE bindings SET base_present = 0, base_flags = NULL, base_object = NULL,"
                        + " base_revision = NULL WHERE collection = ? AND link_id = '119'",
                new Object[] {sent});
        db.execSQL(
                "UPDATE bindings SET base_object = ? WHERE collection = ? AND link_id = '120'",
                new Object[] {own, sent});

        assertEquals("117 on both sides, the inbox's 118 to 120", 5, store.repairLinks(accountId));
        for (String handle : List.of("117", "118", "119", "120")) {
            assertNull("released: inbox " + handle, hashOf(inbox, handle));
        }
        assertNull("released: sent 117", hashOf(sent, "117"));
        for (String handle : List.of("118", "119", "120")) {
            assertNotNull("kept: sent " + handle, hashOf(sent, handle));
        }
    }

    /**
     * What the store-wide lookup left: the sent message filed with the
     * inbox's body of the same UID, its base moved with it.
     */
    private void crossLink(String handle) {
        String hash = hashOf(inbox, handle);
        SQLiteDatabase db = pimdir.getWritableDatabase();
        db.execSQL(
                "UPDATE bindings SET base_object = ? WHERE collection = ? AND link_id = ?",
                new Object[] {hash, sent, handle});
        db.execSQL(
                "UPDATE items SET object_hash = ? WHERE collection = ? AND link_id = ?",
                new Object[] {hash, sent, handle});
    }

    /** The body hash a mailbox's message is stored with, null for none. */
    private String hashOf(String collection, String handle) {
        try (Cursor cursor =
                pimdir.getReadableDatabase()
                        .rawQuery(
                                "SELECT object_hash FROM items WHERE collection = ?"
                                        + " AND link_id = ?",
                                new String[] {collection, handle})) {
            assertTrue(cursor.moveToFirst());
            return cursor.isNull(0) ? null : cursor.getString(0);
        }
    }

    /** The body a mailbox's message is stored with, its headers left out. */
    private String bodyOf(String collection, String handle) {
        return new String(store.storedSource(collection, handle), StandardCharsets.UTF_8)
                .split("\r\n\r\n")[1]
                .trim();
    }

    @Test
    public void aSmallAccountIsHeldWholeAfterItsFirstStep() throws Exception {
        Server server = new Server().mailbox(inbox, 20, 1).mailbox(sent, 30, 3).mailbox(junk, 10, 1);

        MailStep.Outcome first =
                new ImapStep().run(server.engine, new StepRun(EMAIL, collections(), null, true));

        assertNull("all mail", first.frontier);
        assertTrue(first.exhausted);
        assertEquals(20, held(inbox)[1]);
        assertEquals(30, held(sent)[1]);
    }

    @Test
    public void aLaterStepReadsTheBodiesBetweenTheNewFrontierAndTheOld() throws Exception {
        Server server = new Server().mailbox(inbox, 120, 1).mailbox(sent, 30, 3).mailbox(junk, 10, 1);
        MailStep.Outcome first =
                new ImapStep().run(server.engine, new StepRun(EMAIL, collections(), null, true));
        MailWindow.set(context, accountId, first.frontier);
        int readFirst = server.read.size();

        MailStep.Outcome next =
                new ImapStep().run(server.engine, new StepRun(EMAIL, collections(), null, false));

        assertTrue(next.frontier.compareTo(first.frontier) < 0);
        assertEquals(50, next.brought);
        assertEquals("fifty more bodies read", readFirst + 50, server.read.size());
        for (MailBodies.Row row : store.bodyRows(List.of(inbox, sent), next.frontier)) {
            assertTrue(row.collection + "/" + row.linkId + " whole", row.bodied);
        }
        assertEquals(0, held(junk)[1]);
    }

    @Test
    public void aStepCountsTheBodiesItsListingLeftToRead() throws Exception {
        Server server = new Server().mailbox(inbox, 120, 1).mailbox(sent, 30, 3).mailbox(junk, 10, 1);
        List<int[]> told = Collections.synchronizedList(new ArrayList<>());
        server.engine.tally =
                new MailTally((landed, planned) -> told.add(new int[] {landed, planned}));

        new ImapStep().run(server.engine, new StepRun(EMAIL, collections(), null, true));

        assertArrayEquals(
                "planned once the mailboxes are listed", new int[] {0, 50}, told.get(0));
        assertArrayEquals(new int[] {50, 50}, told.get(told.size() - 1));
    }

    @Test
    public void aFirstStepSkipsAMailboxTheServerRefusesToOpen() throws Exception {
        Server server = new Server().mailbox(inbox, 120, 1).mailbox(sent, 30, 3).mailbox(junk, 10, 1);
        server.refused.put(sent, 404);

        MailStep.Outcome first =
                new ImapStep().run(server.engine, new StepRun(EMAIL, collections(), null, true));

        assertEquals("the inbox's 50th newest", server.dateOf(inbox, 71), first.frontier);
        assertFalse(first.exhausted);
        assertEquals(50, held(inbox)[1]);
        assertEquals("nothing of the refused mailbox", 0, held(sent)[0]);
        assertNull("left unopened", store.coverage(sent).at);
        assertNotNull(store.coverage(junk).at);
    }

    @Test
    public void aFirstStepFailsWhenEveryMailboxIsRefusedOrTheConnectionFails() {
        Server refused = new Server().mailbox(inbox, 120, 1).mailbox(sent, 30, 3).mailbox(junk, 10, 1);
        for (String collection : collections()) {
            refused.refused.put(collection, 404);
        }
        PimalayaException all =
                assertThrows(
                        PimalayaException.class,
                        () ->
                                new ImapStep()
                                        .run(
                                                refused.engine,
                                                new StepRun(EMAIL, collections(), null, true)));
        assertEquals(Integer.valueOf(404), all.status);

        Server dropped = new Server().mailbox(inbox, 120, 1).mailbox(sent, 30, 3).mailbox(junk, 10, 1);
        dropped.refused.put(sent, null);
        assertThrows(
                "no refusal, no skip",
                PimalayaException.class,
                () ->
                        new ImapStep()
                                .run(dropped.engine, new StepRun(EMAIL, collections(), null, true)));
    }

    @Test
    public void aLaterStepWidensPastAMailboxTheServerRefusesToOpen() throws Exception {
        Server server = new Server().mailbox(inbox, 120, 1).mailbox(sent, 120, 1).mailbox(junk, 10, 1);
        MailStep.Outcome first =
                new ImapStep().run(server.engine, new StepRun(EMAIL, collections(), null, true));
        MailWindow.set(context, accountId, first.frontier);
        server.refused.put(sent, 404);

        MailStep.Outcome next =
                new ImapStep().run(server.engine, new StepRun(EMAIL, collections(), null, false));

        assertTrue(next.frontier.compareTo(first.frontier) < 0);
        assertFalse("the refused mailbox is not held whole", next.exhausted);
        assertTrue(held(inbox)[1] > 25);
    }
}
