package org.pimalaya;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.pimalaya.client.Card;
import org.pimalaya.client.Mailbox;
import org.pimalaya.client.PimalayaClient;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Deleted items: what the store retains after a deletion, restored as a local
 * creation or purged, through io-pimdir's canonical statements and the real
 * bridge and engine.
 */
@RunWith(RobolectricTestRunner.class)
public class DeletedItemsTest {
    private static final String BOOK = "https://dav.example.com/books/b1/";
    private static final String OTHER = "https://dav.example.com/books/b2/";
    private static final String EMAIL = "jane@example.com";

    private Context context;
    private PimdirDb pimdir;
    private PimdirContacts contacts;
    private DeletedItems deleted;
    private SQLiteDatabase db;

    @Before
    public void setUp() {
        context = RuntimeEnvironment.getApplication();
        pimdir = new PimdirDb(context);
        contacts = new PimdirContacts(pimdir);
        deleted = new DeletedItems(pimdir, context);
        db = pimdir.getWritableDatabase();
        for (String book : new String[] {BOOK, OTHER}) {
            db.execSQL(
                    "INSERT INTO collections(id, account, kind, name)"
                            + " VALUES(?, 'acct', 'text/vcard', ?)",
                    new Object[] {book, book});
        }
    }

    private OfflineEngine engine() {
        return new OfflineEngine(null, pimdir, new PimalayaClient(), null, null, null);
    }

    private static String vcard(String uid, String name) {
        return "BEGIN:VCARD\r\nVERSION:4.0\r\nUID:" + uid + "\r\nFN:" + name
                + "\r\nEMAIL:" + uid + "@example.org\r\nEND:VCARD\r\n";
    }

    /** A card created and deleted before any push: withdrawn, so retained. */
    private void retainedCard(String uid, String name) {
        contacts.save(BOOK, new Card(uid, null, null, vcard(uid, name)));
        contacts.stageDelete(BOOK, uid);
    }

    /** Pretends a sync confirmed the card, its base pinning the body. */
    private void bind(String collection, String linkId, String handle) {
        String object = string(
                "SELECT object_hash FROM items WHERE collection = ? AND link_id = ?",
                collection, linkId);
        db.execSQL(
                "INSERT INTO bindings(collection, link_id, source, handle, base_object,"
                        + " base_revision, base_present) VALUES(?, ?, 'server', ?, ?, 'etag', 1)"
                        + " ON CONFLICT(collection, link_id, source) DO UPDATE SET"
                        + " handle = excluded.handle, base_object = excluded.base_object,"
                        + " base_revision = excluded.base_revision, base_present = 1",
                new Object[] {collection, linkId, handle, object});
        db.execSQL(
                "UPDATE objects SET refcount = refcount + 1 WHERE hash = ?",
                new Object[] {object});
    }

    private long scalar(String sql, String... args) {
        try (Cursor cursor = db.rawQuery(sql, args)) {
            return cursor.moveToFirst() ? cursor.getLong(0) : -1;
        }
    }

    private String string(String sql, String... args) {
        try (Cursor cursor = db.rawQuery(sql, args)) {
            return cursor.moveToFirst() && !cursor.isNull(0) ? cursor.getString(0) : null;
        }
    }

    private DeletedItems.Row only() {
        List<DeletedItems.Row> rows = deleted.list();
        assertEquals(1, rows.size());
        return rows.get(0);
    }

    @Test
    public void aRetainedCardIsListedWithItsSummary() {
        retainedCard("u1", "Jane Doe");

        DeletedItems.Row row = only();
        assertEquals("Jane Doe", row.title);
        assertEquals(BOOK, row.collection.id);
        assertFalse(row.waiting());
        assertTrue(row.stored());
        assertTrue("what a purge would release", deleted.retainedBytes() > 0);
    }

    @Test
    public void restoringIntoTheLastCollectionRevivesTheRow() throws Exception {
        retainedCard("u1", "Jane Doe");
        long seq = scalar("SELECT seq FROM items WHERE link_id = 'u1'");

        deleted.restore(engine(), only(), BOOK);

        // The retained row comes back, its public id kept (STORAGE §11.1),
        // as a create the next sync uploads.
        assertEquals(1, scalar("SELECT count(*) FROM items WHERE link_id = 'u1'"));
        assertEquals(seq, scalar("SELECT seq FROM items WHERE link_id = 'u1'"));
        assertEquals(0, scalar("SELECT count(*) FROM items WHERE link_id = 'u1'"
                + " AND (deleted = 1 OR retained_at IS NOT NULL)"));
        assertEquals(PimdirStorage.provisionalOf("u1"),
                string("SELECT handle FROM bindings WHERE link_id = 'u1'"));
        List<PimdirContacts.Indexed> listed = contacts.list(BOOK);
        assertEquals(1, listed.size());
        assertTrue("pending upload", listed.get(0).unsynced);
        assertTrue(deleted.list().isEmpty());
    }

    @Test
    public void restoringElsewhereAddsANewPlacement() throws Exception {
        retainedCard("u2", "Bob");

        deleted.restore(engine(), only(), OTHER);

        assertEquals(1, contacts.list(OTHER).size());
        assertTrue("the last collection keeps none", contacts.list(BOOK).isEmpty());
        assertEquals("one identity, one public id",
                scalar("SELECT seq FROM items WHERE collection = ? AND link_id = 'u2'", BOOK),
                scalar("SELECT seq FROM items WHERE collection = ? AND link_id = 'u2'", OTHER));
        DeletedItems.Row row = only();
        assertEquals("still retained where it was deleted", BOOK, row.collection.id);
    }

    @Test
    public void restoringWhereTheItemLivesIsRefused() throws Exception {
        retainedCard("u3", "Carol");
        contacts.save(OTHER, new Card("u3", null, null, vcard("u3", "Carol")));

        try {
            deleted.restore(engine(), only(), OTHER);
            fail("restored over a live item");
        } catch (DeletedItems.RefusedException refused) {
            assertEquals(DeletedItems.Refusal.PRESENT, refused.refusal);
        }
    }

    @Test
    public void aWaitingRowIsUntouchedByThePurge() throws Exception {
        // Bound by its server, deleted locally: a tombstone the next sync
        // pushes, still bound, so waiting.
        contacts.save(BOOK, new Card("u4", null, null, vcard("u4", "Dan")));
        bind(BOOK, "u4", "c4.vcf");
        contacts.stageDelete(BOOK, "u4");
        retainedCard("u5", "Erin");
        String purgedObject = string("SELECT object_hash FROM items WHERE link_id = 'u5'");

        List<DeletedItems.Row> rows = deleted.list();
        assertEquals(2, rows.size());
        assertTrue("the waiting row first", rows.get(0).waiting());
        assertEquals("u4", rows.get(0).linkId);
        try {
            deleted.restore(engine(), rows.get(0), BOOK);
            fail("restored a row a source binds");
        } catch (DeletedItems.RefusedException refused) {
            assertEquals(DeletedItems.Refusal.WAITING, refused.refusal);
        }

        assertEquals(1, deleted.purgeBefore("9999-12-31T00:00:00.000Z"));

        assertEquals(0, scalar("SELECT count(*) FROM items WHERE link_id = 'u5'"));
        assertEquals("its body collected", 0,
                scalar("SELECT count(*) FROM objects WHERE hash = ?", purgedObject));
        assertFalse(new PimdirBlobs(pimdir.blobs()).has(purgedObject));
        assertEquals("the waiting row stays, still bound", 1,
                scalar("SELECT count(*) FROM items i JOIN bindings b"
                        + " ON b.collection = i.collection AND b.link_id = i.link_id"
                        + " WHERE i.link_id = 'u4' AND i.deleted = 1"));
        assertEquals(0, deleted.retainedBytes());
        assertTrue(only().waiting());
    }

    // ---- mail -------------------------------------------------------------

    /** One mailbox account; returns its INBOX collection. */
    private String inbox() {
        MailStore store = new MailStore(context, pimdir);
        store.replaceMailboxes(EMAIL, List.of(new Mailbox("INBOX", "inbox")));
        return store.collectionOf(EMAIL, "INBOX");
    }

    private MailEngine mailEngine() {
        return new MailEngine(
                pimdir, new PimalayaClient(), null, new PimdirAccount(context).idOf(EMAIL));
    }

    /** Files one message bound and agreed, then has its server delete it. */
    private void retainedMail(String inbox, String handle, JSONArray flags) throws JSONException {
        String date = "2026-10-07T08:00:00Z";
        JSONObject placement =
                new JSONObject()
                        .put("collection", inbox)
                        .put("handle", handle)
                        .put("linkId", handle)
                        .put("level", "meta")
                        .put("status", "clean")
                        .put(
                                "summary",
                                PimdirSummary.mail(
                                        "<m" + handle + "@example.org>", "Hello", "Ann",
                                        "ann@example.org", null, date, 0, false))
                        .put("sortKey", PimdirSummary.mailSortKey(date))
                        .put("flags", flags)
                        .put("base", new JSONObject().put("flags", flags));
        write(new JSONObject().put("op", "upsert").put("placement", placement));
    }

    private void drop(String inbox, String handle) throws JSONException {
        write(
                new JSONObject()
                        .put("op", "drop")
                        .put("collection", inbox)
                        .put("handle", handle)
                        .put("reason", "deleted"));
    }

    private void write(JSONObject op) throws JSONException {
        String reply =
                mailEngine()
                        .serve(
                                new JSONObject()
                                        .put("op", "write")
                                        .put("writes", new JSONArray().put(op))
                                        .toString());
        assertEquals("{}", reply);
    }

    @Test
    public void aMailNeverOpenedIsNotStoredAndNotRestored() throws Exception {
        String inbox = inbox();
        retainedMail(inbox, "42", new JSONArray());
        drop(inbox, "42");

        DeletedItems.Row row = only();
        assertEquals("Hello", row.title);
        assertEquals("Ann", row.who);
        assertFalse(row.waiting());
        assertFalse("held as summary only", row.stored());
        try {
            deleted.restore(mailEngine(), row, inbox);
            fail("restored a body-less mail");
        } catch (DeletedItems.RefusedException refused) {
            assertEquals(DeletedItems.Refusal.NOT_STORED, refused.refusal);
        }
    }

    @Test
    public void anOpenedMailRestoredIntoItsMailboxRevivesUnderItsHandle() throws Exception {
        String inbox = inbox();
        retainedMail(inbox, "42", new JSONArray().put(MailEngine.SEEN).put(MailEngine.DELETED));
        new MailStore(context, pimdir)
                .saveSource(
                        inbox,
                        "42",
                        "Message-ID: <m42@example.org>\r\nSubject: Hello\r\n\r\nbody\r\n"
                                .getBytes(StandardCharsets.UTF_8));
        drop(inbox, "42");
        long seq = scalar("SELECT seq FROM items WHERE link_id = '42'");

        deleted.restore(mailEngine(), only(), inbox);

        // Its link id is the handle its mailbox gave it, which a restore
        // into the same mailbox names again: the row revives, its public id
        // kept, as a create the next sync appends. The append's arrival is
        // listed under a new handle, so that is where it settles.
        assertEquals(seq, scalar("SELECT seq FROM items WHERE link_id = '42'"));
        assertEquals(0, scalar("SELECT deleted FROM items WHERE link_id = '42'"));
        assertNull(string("SELECT retained_at FROM items WHERE link_id = '42'"));
        String flags = string("SELECT flags FROM items WHERE link_id = '42'");
        assertNotNull(flags);
        assertTrue(flags, flags.contains("Seen"));
        assertFalse("restored, no longer marked deleted", flags.contains("Deleted"));
        assertTrue(deleted.list().isEmpty());
    }

    @Test
    public void aLatin1MailIsRestoredByteForByte() throws Exception {
        String inbox = inbox();
        retainedMail(inbox, "43", new JSONArray());
        byte[] source =
                ("Message-ID: <m43@example.org>\r\nSubject: Café\r\n"
                                + "Content-Type: text/plain; charset=iso-8859-1\r\n"
                                + "Content-Transfer-Encoding: 8bit\r\n\r\ndéjà vu\r\n")
                        .getBytes(StandardCharsets.ISO_8859_1);
        MailStore store = new MailStore(context, pimdir);
        store.saveSource(inbox, "43", source);
        drop(inbox, "43");

        deleted.restore(mailEngine(), only(), inbox);

        assertEquals(0, scalar("SELECT deleted FROM items WHERE link_id = '43'"));
        assertArrayEquals(source, store.storedSource(inbox, "43"));
        assertEquals(PimdirHash.of(source),
                string("SELECT object_hash FROM items WHERE link_id = '43'"));
        assertTrue(deleted.list().isEmpty());
    }
}
