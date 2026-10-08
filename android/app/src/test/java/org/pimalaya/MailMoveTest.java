package org.pimalaya;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.database.Cursor;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.pimalaya.client.Mailbox;
import org.pimalaya.client.PimalayaClient;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Every mail action is a pimdir mutation shown at once, and the sync
 * carries it out as staged (local-first-actions): a move as a server move,
 * a removal as a delete for good, a create as a copy or an append, never
 * one turned into the other. Run through the real bridge, engine and store,
 * the server's verbs recorded rather than sent.
 */
@RunWith(RobolectricTestRunner.class)
public class MailMoveTest {
    private static final String EMAIL = "jane@example.com";

    private Context context;
    private PimdirDb pimdir;
    private MailStore store;
    private String accountId;
    private String inbox;
    private String trash;
    private String sent;

    /** A mail driver whose server verbs are recorded, listing what it is told. */
    private final class Server extends MailEngine {
        final List<String> calls = new ArrayList<>();
        JSONArray listed = new JSONArray();

        Server() {
            super(pimdir, new PimalayaClient(), null, accountId);
        }

        @Override
        protected JSONObject enumerate(JSONObject yielded) throws JSONException {
            JSONObject page =
                    new JSONObject()
                            .put("items", listed)
                            .put("vanished", new JSONArray())
                            .put("complete", true)
                            .put("last", true)
                            .put("checkpoint", "cp");
            nameByMessageId(yielded.getString("collection"), page);
            return page;
        }

        @Override
        protected void relocate(String mailbox, String handle, String target) {
            calls.add("relocate " + mailbox + " " + handle + " " + target);
        }

        @Override
        protected void copy(String mailbox, String handle, String target) {
            calls.add("copy " + mailbox + " " + handle + " " + target);
        }

        @Override
        protected void destroy(String mailbox, String handle) {
            calls.add("destroy " + mailbox + " " + handle);
        }

        @Override
        protected void append(String mailbox, byte[] source, JSONArray flags) {
            calls.add("append " + mailbox);
        }
    }

    @Before
    public void setUp() throws Exception {
        context = RuntimeEnvironment.getApplication();
        pimdir = new PimdirDb(context);
        store = new MailStore(context, pimdir);
        store.replaceMailboxes(
                EMAIL,
                List.of(
                        new Mailbox("INBOX", "inbox"),
                        new Mailbox("Trash", "trash"),
                        new Mailbox("Sent", "sent")));
        accountId = new PimdirAccount(context).idOf(EMAIL);
        inbox = store.collectionOf(EMAIL, "INBOX");
        trash = store.collectionOf(EMAIL, "Trash");
        sent = store.collectionOf(EMAIL, "Sent");

        // One message the sync has reconciled: bound and agreed.
        file(inbox, named("42", "Hello"));
    }

    /** One named member of a listed page, as a mail connector answers it. */
    private static JSONObject named(String handle, String subject) throws JSONException {
        String date = "2026-10-07T08:00:00Z";
        return new JSONObject()
                .put("handle", handle)
                .put("flags", new JSONArray())
                .put("linkId", handle)
                .put(
                        "summary",
                        PimdirSummary.mail(
                                handle + "@example.org", subject, "", "a@example.org", null,
                                date, 0, false))
                .put("sortKey", PimdirSummary.mailSortKey(date));
    }

    /** Files one listed member as the sync would: bound, agreed, clean. */
    private void file(String collection, JSONObject member) throws JSONException {
        JSONObject placement =
                new JSONObject()
                        .put("collection", collection)
                        .put("handle", member.getString("handle"))
                        .put("linkId", member.getString("linkId"))
                        .put("level", "meta")
                        .put("status", "clean")
                        .put("summary", member.getJSONObject("summary"))
                        .put("sortKey", member.getString("sortKey"))
                        .put("flags", new JSONArray())
                        .put("base", new JSONObject().put("flags", new JSONArray()));
        write(new JSONObject().put("op", "upsert").put("placement", placement));
    }

    private void write(JSONObject... ops) throws JSONException {
        JSONArray writes = new JSONArray();
        for (JSONObject op : ops) {
            writes.put(op);
        }
        String reply =
                stager().serve(new JSONObject().put("op", "write").put("writes", writes).toString());
        assertEquals("{}", reply);
    }

    private MailEngine stager() {
        return new MailEngine(pimdir, new PimalayaClient(), null, accountId);
    }

    private String scalar(String sql, String... args) {
        try (Cursor cursor = pimdir.getReadableDatabase().rawQuery(sql, args)) {
            return cursor.moveToFirst() && !cursor.isNull(0) ? cursor.getString(0) : null;
        }
    }

    private String sync(Server server, String collection) {
        return String.valueOf(new PimalayaClient().offlineSyncImmutable(server, collection, null, false));
    }

    @Test
    public void aMoveShowsInTheTargetAtOnceAndPending() throws Exception {
        stager().mutateMove(inbox, "42", trash);

        assertEquals("the source is a tombstone", "1",
                scalar("SELECT deleted FROM items WHERE collection = ? AND link_id = '42'", inbox));
        assertEquals("the target holds it, live", "0",
                scalar("SELECT deleted FROM items WHERE collection = ? AND link_id = '42'", trash));
        assertEquals("with the summary the store holds of it", "Hello",
                scalar("SELECT subject FROM mail_summary WHERE collection = ? AND link_id = '42'",
                        trash));

        List<MailStore.StoredMessage> rows = store.loadMerged(10);
        assertEquals(1, rows.size());
        assertEquals("Trash", rows.get(0).mailbox);
        assertTrue("marked pending", rows.get(0).unsynced);
    }

    @Test
    public void theTombstoneOfAMoveNamesItsDestination() throws Exception {
        stager().mutateMove(inbox, "42", trash);

        JSONArray placements =
                new JSONObject(
                                stager().serve(
                                        new JSONObject()
                                                .put("op", "load")
                                                .put("collection", inbox)
                                                .toString()))
                        .getJSONArray("placements");

        JSONObject source = placements.getJSONObject(0);
        assertEquals("tombstone", source.getString("status"));
        assertEquals(trash, source.getJSONObject("origin").getString("collection"));
    }

    @Test
    public void aMoveIsPushedAsARelocationAndItsTargetWithdrawn() throws Exception {
        stager().mutateMove(inbox, "42", trash);

        Server server = new Server();
        server.listed = new JSONArray().put(named("42", "Hello"));
        sync(server, inbox);

        assertEquals(List.of("relocate INBOX 42 Trash"), server.calls);
        assertNull("the target's create is withdrawn, the arrival brings it",
                scalar("SELECT link_id FROM items WHERE collection = ?", trash));
        assertTrue(store.loadMerged(10).isEmpty());
    }

    @Test
    public void aMoveTargetWaitsForItsSource() throws Exception {
        store.saveSource(inbox, "42", "Subject: Hello\r\n\r\nbody\r\n".getBytes(StandardCharsets.UTF_8));
        stager().mutateMove(inbox, "42", trash);

        Server server = new Server();
        String report = sync(server, trash);

        assertTrue("derived and rejected: " + report, report.contains("\"rejected\":1"));
        assertTrue("no copy, no append: the source's removal delivers it", server.calls.isEmpty());
        assertEquals("still pending", "0",
                scalar("SELECT deleted FROM items WHERE collection = ? AND link_id = '42'", trash));
    }

    @Test
    public void aRemovalInTheTrashIsADeleteForGood() throws Exception {
        file(trash, named("7", "Old"));
        stager().mutateRemove(trash, "7");

        Server server = new Server();
        server.listed = new JSONArray().put(named("7", "Old"));
        sync(server, trash);

        assertEquals(List.of("destroy Trash 7"), server.calls);
    }

    /** The first listed row of one mailbox, null when it lists none. */
    private MailStore.StoredMessage rowIn(String mailbox) {
        for (MailStore.StoredMessage row : store.loadMerged(10)) {
            if (row.mailbox.equals(mailbox)) {
                return row;
            }
        }
        return null;
    }

    @Test
    public void aTrashDeleteOnAServerErasingNoSingleMessageMarksIt() throws Exception {
        file(trash, named("7", "Old"));
        MailStore.markExpungesOne(context, accountId, false);

        assertEquals(MailEngine.Deletion.MARKED, stager().stageDelete(rowIn("Trash")));

        // No UIDPLUS: an EXPUNGE would take every marked message, so the row
        // stays, saying so, rather than leaving and coming back.
        MailStore.StoredMessage row = rowIn("Trash");
        assertTrue(row.deleted);
        assertTrue(row.unsynced);
        assertEquals("not a tombstone", "0",
                scalar("SELECT deleted FROM items WHERE collection = ? AND link_id = '7'", trash));
    }

    @Test
    public void aTrashDeleteOnAServerErasingOneMessageRemovesIt() throws Exception {
        file(trash, named("7", "Old"));
        MailStore.markExpungesOne(context, accountId, true);

        assertEquals(MailEngine.Deletion.ERASED, stager().stageDelete(rowIn("Trash")));
        assertNull(rowIn("Trash"));
    }

    @Test
    public void aSentCopyIsHiddenWhileItsSubmissionWaits() throws Exception {
        String date = "2026-10-08T09:00:00Z";
        String body = "Message-ID: <m2@example.com>\r\nSubject: Hi\r\n\r\nbody\r\n";
        store.queueSubmission(EMAIL, "m2@example.com", "Hi", PimdirSummary.mailSortKey(date),
                body.getBytes(StandardCharsets.UTF_8));
        stager().mutateAdd(
                sent,
                "m2@example.com",
                body,
                new JSONArray().put(MailEngine.SEEN),
                PimdirSummary.mail("m2@example.com", "Hi", null, EMAIL, "bob@example.org",
                        date, body.length(), false),
                PimdirSummary.mailSortKey(date));

        MailStore.Query everything = store.query((account, mailbox) -> true, false, false, "");
        assertNull("the outbox row stands for it", rowIn("Sent"));
        assertEquals("the inbox's message alone", 1, store.count(everything));
        assertEquals("a whole page past the hidden newer row", "INBOX",
                store.page(everything, null, 0, 1).get(0).mailbox);
        assertEquals("Sent alone, no outbox row stands for it", 1,
                store.count(store.query(
                        (account, collection) ->
                                collection.equals(store.collectionOf(EMAIL, "Sent")),
                        false,
                        false,
                        "")));

        store.acknowledge(store.outgoing().get(0).queued);

        assertTrue("shown once the submission went", rowIn("Sent").unsynced);
        assertEquals(2, store.count(store.query((account, mailbox) -> true, false, false, "")));
    }

    @Test
    public void aCopyIsPushedServerSide() throws Exception {
        stager().mutateCopy(inbox, "42", trash);

        Server server = new Server();
        String report = sync(server, trash);

        assertEquals(report, List.of("copy INBOX 42 Trash"), server.calls);
        assertNull("accepted with no handle, the arrival brings it",
                scalar("SELECT link_id FROM items WHERE collection = ?", trash));
        assertEquals("the source stays", "0",
                scalar("SELECT deleted FROM items WHERE collection = ? AND link_id = '42'", inbox));
    }

    @Test
    public void aSentCopyLandsOnTheArrivalCarryingItsMessageId() throws Exception {
        String date = "2026-10-08T09:00:00Z";
        String body = "Message-ID: <m1@example.com>\r\nSubject: Hi\r\n\r\nbody\r\n";
        stager().mutateAdd(
                sent,
                "m1@example.com",
                body,
                new JSONArray().put(MailEngine.SEEN),
                PimdirSummary.mail("m1@example.com", "Hi", null, EMAIL, "bob@example.org",
                        date, body.length(), false),
                PimdirSummary.mailSortKey(date));
        assertTrue(store.loadMerged(10).get(0).unsynced);

        // The provider filed it itself under its own handle.
        JSONObject arrival =
                new JSONObject()
                        .put("handle", "900")
                        .put("flags", new JSONArray().put(MailEngine.SEEN))
                        .put("linkId", "900")
                        .put(
                                "summary",
                                PimdirSummary.mail("m1@example.com", "Hi", null, EMAIL,
                                        "bob@example.org", date, body.length(), false))
                        .put("sortKey", PimdirSummary.mailSortKey(date));
        Server server = new Server();
        server.listed = new JSONArray().put(arrival);
        sync(server, sent);

        assertTrue("landed, not appended", server.calls.isEmpty());
        assertEquals("filed under the arrival's handle", "900",
                scalar("SELECT link_id FROM items WHERE collection = ?", sent));
        assertEquals("1", scalar("SELECT count(*) FROM items WHERE collection = ?", sent));
        assertEquals("Hi",
                scalar("SELECT subject FROM mail_summary WHERE collection = ?", sent));
        assertFalse(store.loadMerged(10).get(0).unsynced);
    }

    @Test
    public void aCalendarRefusesARelocation() throws Exception {
        String calendar = PimdirAccount.collectionId(accountId, "https://dav.example.org/cal/");
        CalendarEngine engine =
                new CalendarEngine(pimdir, new PimalayaClient(), null, null, accountId);
        JSONObject change =
                new JSONObject()
                        .put("op", "remove")
                        .put("key", "k")
                        .put("handle", "a.ics")
                        .put("to", "elsewhere")
                        .put("linkId", "a.ics");
        JSONObject reply =
                new JSONObject(
                        engine.serve(
                                new JSONObject()
                                        .put("op", "push")
                                        .put("collection", calendar)
                                        .put("changes", new JSONArray().put(change))
                                        .toString()));

        JSONObject result = reply.getJSONArray("results").getJSONObject(0);
        assertFalse("refused rather than deleted", result.getBoolean("accepted"));
        assertTrue(Refusals.refused(context, calendar, "a.ics"));
    }
}
