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
import org.pimalaya.client.Mailbox;
import org.pimalaya.client.PimalayaException;
import org.pimalaya.client.PimalayaClient;
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
 * An IMAP step from the engine down to the store: the first one takes the
 * inbox's floor, lists every mailbox from it and reads the bodies above
 * it, the junk's left out; a later one widens from the store and reads the
 * bodies between the new frontier and the old.
 *
 * <p>The server is a few mailboxes of dated messages answering the
 * engine's listings, the chunk floors the bridge would ask for and the
 * files it would write, so everything from the step down to the store is
 * the real thing.
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
     * An account whose mailboxes each hold {@code size} messages, UIDs 1 to
     * {@code size}, the newest at the top and each {@code spacing} hours
     * older than the one above it, each mailbox 20 minutes below the one
     * declared before it, so no two messages share a date.
     */
    private final class Server extends MailEngine {
        final Map<String, int[]> shapes = new HashMap<>();
        final List<String> read = new ArrayList<>();

        /** Mailboxes the server refuses to open, and how. */
        final Map<String, Integer> refused = new HashMap<>();

        Server() {
            super(pimdir, new PimalayaClient(), null, accountId);
        }

        Server mailbox(String collection, int size, int spacing) {
            shapes.put(collection, new int[] {size, spacing, shapes.size() * 20});
            return this;
        }

        /**
         * The EXAMINE a read opens a mailbox with: refused with a 404 as the
         * bridge answers Gmail's {@code NO Unknown Mailbox}, or failing as a
         * dropped connection does, with no status.
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
        String floor(String collection, String before, int count) {
            open(collection);
            String oldest = null;
            int taken = 0;
            for (int uid = shapes.get(collection)[0]; uid >= 1 && taken < count; uid--) {
                String date = dateOf(collection, uid);
                if (before == null || date.compareTo(before) < 0) {
                    taken++;
                    oldest = date;
                }
            }
            return taken < count ? null : oldest;
        }

        @Override
        List<String> newest(String collection, int count) {
            open(collection);
            List<String> newest = new ArrayList<>();
            for (int uid = shapes.get(collection)[0]; uid >= 1 && newest.size() < count; uid--) {
                newest.add(dateOf(collection, uid));
            }
            return newest;
        }

        @Override
        protected JSONObject enumerate(JSONObject yielded) throws JSONException {
            String collection = yielded.getString("collection");
            open(collection);
            JSONObject listing = yielded.getJSONObject("listing");
            JSONObject scope = yielded.optJSONObject("scope");
            String since = scope == null || scope.isNull("since") ? null : scope.optString("since");
            String until = scope == null || scope.isNull("until") ? null : scope.optString("until");
            JSONObject page =
                    new JSONObject().put("vanished", new JSONArray()).put("last", true);
            if ("delta".equals(listing.getString("kind"))) {
                return page.put("items", new JSONArray()).put("complete", false)
                        .put("checkpoint", "cp");
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
            if (!listing.optBoolean("band")) {
                page.put("checkpoint", "cp");
            }
            return page;
        }

        @Override
        protected Map<String, File> sources(String mailbox, List<String> handles, File dir) {
            String collection = collectionOf(mailbox);
            Map<String, File> files = new HashMap<>();
            for (String handle : handles) {
                read.add(collection + "/" + handle);
                File file = new File(dir, handle + ".eml");
                try {
                    Files.write(file.toPath(), source(collection, handle));
                } catch (IOException failure) {
                    throw new AssertionError(failure);
                }
                files.put(handle, file);
            }
            return files;
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

        @Override
        protected JSONObject push(JSONObject yielded) throws JSONException {
            return new JSONObject().put("results", new JSONArray());
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

        MailStep.Outcome first = new ImapStep().run(server, EMAIL, collections(), null, true);

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

        MailStep.Outcome first = new ImapStep().run(server, EMAIL, collections(), null, true);

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
        new ImapStep().run(server, EMAIL, collections(), null, true);

        // NOTE: what the store-wide lookup left: the sent UID 120 filed
        // with the inbox's UID 120 body, its base moved with it.
        SQLiteDatabase db = pimdir.getWritableDatabase();
        String hash;
        try (Cursor cursor =
                db.rawQuery(
                        "SELECT object_hash FROM items WHERE collection = ? AND link_id = '120'",
                        new String[] {inbox})) {
            assertTrue(cursor.moveToFirst());
            hash = cursor.getString(0);
        }
        db.execSQL(
                "UPDATE bindings SET base_object = ? WHERE collection = ? AND link_id = '120'",
                new Object[] {hash, sent});
        db.execSQL(
                "UPDATE items SET object_hash = ? WHERE collection = ? AND link_id = '120'",
                new Object[] {hash, sent});
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
            server.download(collection, rows);
        }
        assertEquals(List.of(inbox + "/120", sent + "/120"), server.read);
        assertEquals(server.body(inbox, "120"), bodyOf(inbox, "120"));
        assertEquals(server.body(sent, "120"), bodyOf(sent, "120"));
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

        MailStep.Outcome first = new ImapStep().run(server, EMAIL, collections(), null, true);

        assertNull("all mail", first.frontier);
        assertTrue(first.exhausted);
        assertEquals(20, held(inbox)[1]);
        assertEquals(30, held(sent)[1]);
    }

    @Test
    public void aLaterStepReadsTheBodiesBetweenTheNewFrontierAndTheOld() throws Exception {
        Server server = new Server().mailbox(inbox, 120, 1).mailbox(sent, 30, 3).mailbox(junk, 10, 1);
        MailStep.Outcome first = new ImapStep().run(server, EMAIL, collections(), null, true);
        MailWindow.set(context, accountId, first.frontier);
        int readFirst = server.read.size();

        MailStep.Outcome next = new ImapStep().run(server, EMAIL, collections(), null, false);

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
        server.tally = new MailTally((landed, planned) -> told.add(new int[] {landed, planned}));

        new ImapStep().run(server, EMAIL, collections(), null, true);

        assertArrayEquals(
                "planned once the mailboxes are listed", new int[] {0, 50}, told.get(0));
        assertArrayEquals(new int[] {50, 50}, told.get(told.size() - 1));
    }

    @Test
    public void aFirstStepSkipsAMailboxTheServerRefusesToOpen() throws Exception {
        Server server = new Server().mailbox(inbox, 120, 1).mailbox(sent, 30, 3).mailbox(junk, 10, 1);
        server.refused.put(sent, 404);

        MailStep.Outcome first = new ImapStep().run(server, EMAIL, collections(), null, true);

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
                        () -> new ImapStep().run(refused, EMAIL, collections(), null, true));
        assertEquals(Integer.valueOf(404), all.status);

        Server dropped = new Server().mailbox(inbox, 120, 1).mailbox(sent, 30, 3).mailbox(junk, 10, 1);
        dropped.refused.put(sent, null);
        assertThrows(
                "no refusal, no skip",
                PimalayaException.class,
                () -> new ImapStep().run(dropped, EMAIL, collections(), null, true));
    }

    @Test
    public void aLaterStepWidensPastAMailboxTheServerRefusesToOpen() throws Exception {
        Server server = new Server().mailbox(inbox, 120, 1).mailbox(sent, 120, 1).mailbox(junk, 10, 1);
        MailStep.Outcome first = new ImapStep().run(server, EMAIL, collections(), null, true);
        MailWindow.set(context, accountId, first.frontier);
        server.refused.put(sent, 404);

        MailStep.Outcome next = new ImapStep().run(server, EMAIL, collections(), null, false);

        assertTrue(next.frontier.compareTo(first.frontier) < 0);
        assertFalse("the refused mailbox is not held whole", next.exhausted);
        assertTrue(held(inbox)[1] > 25);
    }
}
