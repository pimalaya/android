package org.pimalaya.mail;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.pimalaya.client.Mailbox;
import org.pimalaya.client.SearchCoverage;
import org.pimalaya.pimdir.ItemFixtures;
import org.pimalaya.pimdir.PimdirAccount;
import org.pimalaya.pimdir.PimdirDb;
import org.pimalaya.pimdir.PimdirIndexDb;
import org.pimalaya.pimdir.PimdirItems;
import org.pimalaya.pimdir.PimdirStorage;
import org.pimalaya.pimdir.PimdirSummary;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * The search index on the platform's classes over Android 8.0's SQLite
 * 3.18.2 (the test shadows): index.db built, refreshed and asked through
 * {@link PimdirIndexDb}, and the mail search reading it beside the sender
 * and subject match.
 */
@RunWith(RobolectricTestRunner.class)
public class MailIndexTest {
    private static final String ONE = "jane@example.com";
    private static final String DATE = "Mon, 5 Jan 2026 09:00:00 +0000";

    private PimdirDb pimdir;
    private PimdirItems items;
    private ItemFixtures fixtures;
    private MailStore store;

    @Before
    public void setUp() {
        Context context = RuntimeEnvironment.getApplication();
        pimdir = new PimdirDb(context);
        items = new PimdirItems(pimdir);
        fixtures = new ItemFixtures(pimdir);
        store = new MailStore(context, pimdir);
        store.replaceMailboxes(ONE, List.of(new Mailbox("INBOX", "")));
    }

    /** Files one message's summary, and its body when one is given. */
    private void message(String id, String subject, String body) {
        String collection = store.collectionOf(ONE, "INBOX");
        SQLiteDatabase db = items.writable();
        db.beginTransaction();
        try {
            fixtures.put(
                    db,
                    collection,
                    new ItemFixtures.Row(
                            id,
                            null,
                            PimdirSummary.mail(
                                    "<" + id + "@x>", subject, "Sender", "sender@example.org",
                                    null, DATE, 0, false),
                            PimdirSummary.mailSortKey(DATE),
                            new JSONArray().toString(),
                            null));
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
        if (body != null) {
            String source =
                    "Message-ID: <" + id + "@x>\r\nSubject: " + subject
                            + "\r\nFrom: Sender <sender@example.org>\r\nDate: " + DATE
                            + "\r\n\r\n" + body + "\r\n";
            fixtures.putObject(collection, id, source.getBytes(StandardCharsets.UTF_8));
        }
    }

    private MailStore.Query search(String words) {
        return store.query((account, mailbox) -> true, false, false, words);
    }

    private File indexFile() {
        return new File(pimdir.blobs().getParentFile(), "index.db");
    }

    @Test
    public void theMailSearchFindsABodyBesideTheSenderAndSubject() {
        message("a", "Weekly", "the roadmap for the quarter");
        message("b", "Roadmap draft", null);
        message("c", "Re: Weekly", null);

        // NOTE: no index, the search is the sender and subject one alone.
        assertEquals(1, store.count(search("roadmap")));

        PimdirIndexDb index = pimdir.startIndex();
        JSONObject report = index.refresh();
        assertTrue(report.optBoolean("exhausted"));
        assertEquals(3, report.optLong("folded"));

        MailStore.Query found = search("roadmap");
        assertNull(found.refusal);
        assertEquals("a body and a subject", 2, store.count(found));
        assertEquals(2, store.page(found, null, 0, 50).size());
        assertEquals(1, store.countByDay(found, 0).size());
        assertEquals("a word inside another, as before", 1, store.count(search("oadma")));
        assertEquals("a field of the language", 1, store.count(search("body:quarter")));

        // NOTE: a query the language refuses says why, and the sender and
        // subject match still answers.
        MailStore.Query refused = search("re: weekly");
        assertNotNull(refused.refusal);
        assertEquals(1, store.count(refused));
        assertEquals("c", store.page(refused, null, 0, 50).get(0).id);

        assertTrue(indexFile().isFile());
        assertFalse("index.db keeps no write-ahead log", new File(indexFile() + "-wal").exists());
    }

    @Test
    public void aHitCarriesASnippetOfItsBody() throws Exception {
        message("a", "Weekly", "the roadmap for the quarter");
        PimdirIndexDb index = pimdir.startIndex();
        index.refresh();

        JSONObject page = index.search("quarter", "date", 10, null);
        JSONArray hits = page.getJSONArray("hits");
        assertEquals(1, hits.length());
        JSONObject hit = hits.getJSONObject(0);
        assertEquals("mail", hit.getString("kind"));
        assertEquals("Weekly", hit.getString("title"));
        assertTrue(hit.getString("snippet").contains("the roadmap for the quarter"));
        assertFalse(hit.has("account") && hit.isNull("account"));
        assertFalse(page.getBoolean("more"));

        JSONObject limit = index.hits("x ".repeat(600), "date");
        assertEquals("limit-exceeded", limit.getJSONObject("refused").getString("code"));
    }

    /** A round over {@code collection} from {@code since}, opened and closed. */
    private void listed(String collection, String since) throws Exception {
        JSONObject scope = new JSONObject().put("since", since);
        new PimdirStorage(pimdir)
                .applyWrites(
                        new JSONArray()
                                .put(new JSONObject()
                                        .put("op", "openRound")
                                        .put("collection", collection)
                                        .put("scope", scope))
                                .put(new JSONObject()
                                        .put("op", "closeRound")
                                        .put("collection", collection)
                                        .put("coverage", scope)));
    }

    @Test
    public void aSearchSaysWhatItCouldNotSee() throws Exception {
        message("a", "Weekly", "the roadmap for the quarter");
        message("b", "Roadmap draft", null);
        PimdirIndexDb index = pimdir.startIndex();
        index.refresh();

        assertNull("nothing searched", search("").coverage());
        SearchCoverage never = search("roadmap").coverage();
        assertNotNull(never);
        assertFalse(never.complete());
        assertTrue("an inbox the server never listed", never.filling());
        assertNull("no floor, never the text null", never.since());

        String inbox = store.collectionOf(ONE, "INBOX");
        listed(inbox, "2026-01-01T00:00:00Z");
        index.refresh();
        SearchCoverage floor = search("roadmap").coverage();
        assertFalse(floor.complete());
        assertFalse(floor.filling());
        assertEquals(1, floor.shortAccounts());
        assertEquals(PimdirAccount.accountIdOf(inbox), floor.worst());
        assertEquals("2026-01-01T00:00:00Z", floor.since());
        assertFalse(floor.bounded());
        assertEquals("the draft held bodiless", 1, floor.unread());
        assertEquals(1, floor.accounts().size());

        SearchCoverage structured = search("from:sender@example.org date:2026-01-02..").coverage();
        assertTrue(
                "no body read, a range above the floor: " + structured, structured.complete());
    }

    /** One value of index.db's index_meta, read off the file. */
    private void meta(String assignment) {
        SQLiteDatabase db =
                SQLiteDatabase.openDatabase(
                        indexFile().getAbsolutePath(), null, SQLiteDatabase.OPEN_READWRITE);
        try {
            db.execSQL("UPDATE index_meta SET " + assignment);
        } finally {
            db.close();
        }
    }

    private String shape() {
        SQLiteDatabase db =
                SQLiteDatabase.openDatabase(
                        indexFile().getAbsolutePath(), null, SQLiteDatabase.OPEN_READONLY);
        try (Cursor cursor = db.rawQuery("SELECT shape, format_version FROM index_meta", null)) {
            assertTrue(cursor.moveToFirst());
            return cursor.getString(0) + " " + cursor.getString(1);
        } finally {
            db.close();
        }
    }

    @Test
    public void anIndexOfAnotherShapeIsRefusedAndAnOlderOneRebuilt() {
        message("a", "Weekly", "the roadmap for the quarter");
        PimdirIndexDb built = pimdir.startIndex();
        built.refresh();
        assertEquals("pimalaya-android search-v1", shape());
        built.close();

        meta("shape = 'canonical'");
        PimdirIndexDb other = new PimdirIndexDb(pimdir);
        assertNull("no hit from an index of another shape", other.hits("roadmap", "date"));
        assertNotNull(other.refused());
        assertEquals("left as it was", "canonical search-v1", shape());

        meta("shape = 'pimalaya-android', format_version = 'search-draft-17'");
        PimdirIndexDb older = new PimdirIndexDb(pimdir);
        older.refresh();
        assertNull(older.refused());
        assertEquals("pimalaya-android search-v1", shape());
        assertEquals(1, older.hits("roadmap", "date").optJSONArray("hits").length());
        older.close();
    }
}
