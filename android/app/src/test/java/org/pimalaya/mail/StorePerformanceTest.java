package org.pimalaya.mail;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.database.Cursor;

import io.requery.android.database.sqlite.SQLiteDatabase;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.pimalaya.client.AccountClient;
import org.pimalaya.client.CalendarClient;
import org.pimalaya.client.Clients;
import org.pimalaya.client.ContactClient;
import org.pimalaya.client.Flags;
import org.pimalaya.client.MailClient;
import org.pimalaya.client.MailSession;
import org.pimalaya.client.Mailbox;
import org.pimalaya.client.MessageSource;
import org.pimalaya.client.MessageSources;
import org.pimalaya.client.NamedStep;
import org.pimalaya.client.OfflineClient;
import org.pimalaya.client.OfflineDriver;
import org.pimalaya.client.PimdirSql;
import org.pimalaya.client.StepRequest;
import org.pimalaya.file.FileStore;
import org.pimalaya.pimdir.DeletedItems;
import org.pimalaya.pimdir.PimdirDb;
import org.pimalaya.pimdir.PimdirHash;
import org.pimalaya.pimdir.PimdirStorage;
import org.pimalaya.pimdir.PimdirSummary;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A store read costs what it asks for, not what the store holds.
 *
 * <p>Scoped loads read their page, writes never go through a cursor, a deep
 * page or fling reads from where it lands, counts skip unfiltered summaries,
 * steps ask which of a page is held (cairn/changes/store-performance).
 */
@RunWith(RobolectricTestRunner.class)
public class StorePerformanceTest {
    private static final String EMAIL = "jane@example.com";

    private Context context;
    private PimdirDb pimdir;
    private PimdirStorage storage;
    private MailStore store;
    private SQLiteDatabase db;
    private String inbox;
    private String archive;

    @Before
    public void setUp() {
        context = RuntimeEnvironment.getApplication();
        pimdir = new PimdirDb(context);
        storage = new PimdirStorage(pimdir);
        store = new MailStore(context, pimdir);
        store.replaceMailboxes(
                EMAIL, List.of(new Mailbox("INBOX", "inbox"), new Mailbox("Archive", "archive")));
        inbox = store.collectionOf(EMAIL, "INBOX");
        archive = store.collectionOf(EMAIL, "Archive");
        db = pimdir.getWritableDatabase();
    }

    /** One listed message as the engine writes it: bound, based, named. */
    private static JSONObject listed(String collection, String handle, String date)
            throws Exception {
        JSONObject placement = new JSONObject();
        placement.put("collection", collection);
        placement.put("handle", handle);
        placement.put("linkId", handle);
        placement.put("level", "meta");
        placement.put("status", "clean");
        placement.put("flags", new JSONArray().put(Flags.Marker.SEEN.wire()));
        placement.put(
                "summary",
                PimdirSummary.mail(
                        null, "Message " + handle, "Sender", "sender@example.org", null, date, 10,
                        false));
        placement.put("sortKey", PimdirSummary.mailSortKey(date));
        placement.put("base", new JSONObject().put("flags", new JSONArray().put(Flags.Marker.SEEN.wire())));
        return new JSONObject().put("op", "upsert").put("placement", placement);
    }

    /** Lists {@code count} messages, one a minute down from {@code newest}. */
    private void list(String collection, String prefix, int count, String newest)
            throws Exception {
        java.time.Instant top = java.time.Instant.parse(newest);
        JSONArray writes = new JSONArray();
        for (int index = 0; index < count; index++) {
            String date = top.minusSeconds(60L * index).toString();
            writes.put(listed(collection, prefix + index, date));
        }
        storage.applyWrites(writes);
    }

    private long scalar(String sql, String... args) {
        try (Cursor cursor = db.rawQuery(sql, args)) {
            return cursor.moveToFirst() ? cursor.getLong(0) : -1;
        }
    }


    @Test
    public void aScopeLongerThanAStatementBindsStillNarrowsTheLoad() throws Exception {
        list(inbox, "h", 1500, "2026-10-01T12:00:00Z");
        JSONArray handles = new JSONArray();
        JSONArray links = new JSONArray();
        for (int index = 0; index < 1000; index++) {
            handles.put("h" + index);
            links.put("h" + (index + 300));
        }

        // NOTE: a listing's page is 500 to 1000 members: a scope that long used
        // to widen back to the whole mailbox, every page then reading all of
        // it.
        JSONObject byHandle = new JSONObject().put("kind", "handles").put("handles", handles);
        JSONArray loaded = storage.loadCollection(inbox, byHandle).getJSONArray("placements");
        assertEquals(1000, loaded.length());
        JSONObject byLink = new JSONObject().put("kind", "links").put("links", links);
        assertEquals(
                1000, storage.loadCollection(inbox, byLink).getJSONArray("placements").length());
    }

    @Test
    public void aHandleScopeReachesAStagedCreateByItsName() throws Exception {
        list(inbox, "h", 3, "2026-10-01T12:00:00Z");
        JSONObject staged =
                new JSONObject()
                        .put("collection", inbox)
                        .put("handle", PimdirStorage.provisionalOf("draft"))
                        .put("linkId", "draft")
                        .put("object", "aa11")
                        .put("level", "full")
                        .put("status", "clean")
                        .put("flags", new JSONArray());
        storage.applyWrites(
                new JSONArray()
                        .put(
                                new JSONObject()
                                        .put("op", "storeObject")
                                        .put("hash", "aa11")
                                        .put("body", "From: a@example.org\r\n\r\nbody\r\n"))
                        .put(new JSONObject().put("op", "upsert").put("placement", staged)));

        JSONObject scope =
                new JSONObject()
                        .put("kind", "handles")
                        .put(
                                "handles",
                                new JSONArray()
                                        .put(PimdirStorage.provisionalOf("draft"))
                                        .put("h1"));
        JSONArray loaded = storage.loadCollection(inbox, scope).getJSONArray("placements");
        List<String> got = new ArrayList<>();
        for (int index = 0; index < loaded.length(); index++) {
            got.add(loaded.getJSONObject(index).getString("handle"));
        }
        java.util.Collections.sort(got);
        assertEquals(List.of(PimdirStorage.provisionalOf("draft"), "h1"), got);
    }


    /** Seeds {@code count} listed messages straight into the tables. */
    private void seed(String collection, int from, int count, String date, String object) {
        String rows =
                "WITH RECURSIVE n(i) AS (SELECT " + (from + 1) + " UNION ALL SELECT i + 1 FROM n"
                        + " WHERE i < " + (from + count) + ") ";
        if (object != null) {
            db.execSQL(
                    rows + "INSERT INTO objects(hash, size, refcount) SELECT ? || i, 10, 1 FROM n",
                    new Object[] {object});
        }
        db.execSQL(
                rows + "INSERT INTO items(collection, link_id, seq, flags, object_hash, sort_key,"
                        + " level) SELECT ?, 'm' || i, i, '[]', ? || i, ?, ? FROM n",
                new Object[] {collection, object, date, object == null ? 1 : 2});
        db.execSQL(
                rows + "INSERT INTO bindings(collection, link_id, source, handle, base_flags,"
                        + " base_object, base_present) SELECT ?, 'm' || i, 'server', 'h' || i,"
                        + " '[]', ? || i, 1 FROM n",
                new Object[] {collection, object});
        db.execSQL(
                rows + "INSERT INTO mail_summary(collection, link_id, subject, date)"
                        + " SELECT ?, 'm' || i, 's', ? FROM n",
                new Object[] {collection, date});
    }

    /** More rows than one 2 MB cursor window holds of a seq (87,150). */
    private static final int PAST_A_WINDOW = 250_000;

    @Test(timeout = 120_000)
    public void aCollectionPastACursorWindowDeletesOnceAndCountsEveryRow() {
        seed(inbox, 0, PAST_A_WINDOW, "2001-01-01T00:00:00Z", null);
        seed(inbox, PAST_A_WINDOW, PAST_A_WINDOW, "2003-01-01T00:00:00Z", null);

        // NOTE: a delete read through a cursor runs again for every row past
        // its first window, each run reading the mailbox: a collection that
        // never ends.
        assertEquals(PAST_A_WINDOW, store.collectBefore(EMAIL, "2002-01-01T00:00:00Z"));
        assertEquals(PAST_A_WINDOW, scalar("SELECT count(*) FROM items"));
        assertEquals(PAST_A_WINDOW, scalar("SELECT count(*) FROM bindings"));
    }

    @Test(timeout = 120_000)
    public void aReleasePastACursorWindowFreesEveryBody() {
        seed(inbox, 0, PAST_A_WINDOW, "2001-01-01T00:00:00Z", "body");

        assertEquals(PAST_A_WINDOW, store.release(EMAIL, "2002-01-01T00:00:00Z"));
        assertEquals(0, scalar("SELECT count(*) FROM items WHERE object_hash IS NOT NULL"));
        assertEquals("the body went to the collector", 0, scalar("SELECT count(*) FROM objects"));
    }

    @Test
    public void aPurgePastACursorWindowLetsGoOfEveryPin() {
        db.execSQL(
                "INSERT INTO objects(hash, size, refcount) VALUES('pinned0', 10, " + PAST_A_WINDOW + ")");
        db.execSQL(
                "WITH RECURSIVE n(i) AS (SELECT 1 UNION ALL SELECT i + 1 FROM n WHERE i < "
                        + PAST_A_WINDOW + ") INSERT INTO items(collection, link_id, seq,"
                        + " object_hash, level, deleted, retained_at)"
                        + " SELECT ?, 'r' || i, i, 'pinned0', 2, 1, '2001-01-01T00:00:00Z' FROM n",
                new Object[] {inbox});

        DeletedItems deleted = new DeletedItems(pimdir, context);
        assertEquals(PAST_A_WINDOW, deleted.purgeBefore("2002-01-01T00:00:00Z"));
        assertEquals(0, scalar("SELECT count(*) FROM items"));
        assertEquals("no pin is left holding the body", 0, scalar("SELECT count(*) FROM objects"));
    }

    @Test
    public void theWriteHelperCountsWithoutReturning() {
        seed(inbox, 0, 3, "2001-01-01T00:00:00Z", null);
        Map<String, Object> values = new HashMap<>();
        values.put("collection", inbox);
        values.put("before", "2002-01-01T00:00:00Z");
        PimdirSql.Bound bound = PimdirSql.bind("COLLECT_BEFORE", values);
        assertTrue("the canonical statement returns its rows", bound.sql.contains("RETURNING"));
        assertEquals(3, PimdirDb.changed(db, bound));
        assertEquals(0, scalar("SELECT count(*) FROM items"));
    }


    /** EXPLAIN QUERY PLAN of a bound read, its steps joined. */
    private String plan(PimdirSql.Bound bound) {
        StringBuilder plan = new StringBuilder();
        try (Cursor cursor = PimdirDb.typed(db, "EXPLAIN QUERY PLAN " + bound.sql, bound.args)) {
            while (cursor.moveToNext()) {
                plan.append(cursor.getString(3)).append('\n');
            }
        }
        return plan.toString();
    }

    @Test
    public void aKeysetPageSeeksTheSortKeyItReadsBelow() {
        MailStore.Query query = store.query((account, collection) -> true, false, false, "");
        Map<String, Object> values = query.values(null, 50);
        values.put("after_key", "2026-01-01T00:00:00Z");
        values.put("after_seq", 7L);
        values.put("after_collection", inbox);

        String canonical = plan(PimdirSql.bind("LIST_MAIL_PAGE_FILTERED", values));
        assertFalse(canonical, canonical.contains("sort_key>? AND sort_key<?"));
        String bounded =
                plan(MailStore.below(
                        PimdirSql.bind("LIST_MAIL_PAGE_FILTERED", values), "2026-01-01T00:00:00Z"));
        assertTrue(bounded, bounded.contains("items_by_sort_global (sort_key>? AND sort_key<?)"));
    }

    @Test
    public void keysetPagesFlingsAndTheBodyPlanWalkTheListAsOneRead() throws Exception {
        list(inbox, "i", 130, "2026-03-02T00:30:00Z");
        list(archive, "a", 70, "2026-03-02T00:30:00Z");
        storage.applyWrites(
                new JSONArray()
                        .put(listed(inbox, "u1", "not a date"))
                        .put(listed(archive, "u2", "not a date")));
        MailStore.Query query = store.query((account, collection) -> true, false, false, "");
        List<MailStore.StoredMessage> whole = store.page(query, null, 0, 1000);
        assertEquals(202, whole.size());

        List<String> walked = new ArrayList<>();
        MailStore.StoredMessage after = null;
        while (true) {
            List<MailStore.StoredMessage> page = store.page(query, after, 0, 7);
            for (MailStore.StoredMessage message : page) {
                walked.add(message.collection + "/" + message.id);
            }
            if (page.size() < 7) {
                break;
            }
            after = page.get(page.size() - 1);
        }
        assertEquals(ids(whole), walked);

        List<String> planned = new ArrayList<>();
        for (MailBodies.Row row : store.bodyRows(List.of(inbox, archive), null)) {
            planned.add(row.collection + "/" + row.linkId);
        }
        assertEquals(ids(whole), planned);

        // NOTE: a fling lands on a row with no page above it read: from the
        // start of its day on the wall clock the days were counted on.
        for (int minutes : new int[] {0, 120, -300}) {
            String shift = String.format(java.util.Locale.ROOT, "%+d minutes", minutes);
            List<MailStore.Day> days = store.countByDay(query, shift);
            for (int row = 0; row < whole.size(); row += 5) {
                MailList.Seek seek = MailList.seek(days, minutes, row);
                List<MailStore.StoredMessage> landed =
                        store.pageBelow(query, seek.key, seek.skip, 3);
                List<String> expected =
                        ids(whole).subList(row, Math.min(whole.size(), row + 3));
                assertEquals("row " + row + " at " + shift, expected, ids(landed));
            }
            assertNull(MailList.seek(days, minutes, whole.size()));
        }
    }

    private static List<String> ids(List<MailStore.StoredMessage> messages) {
        List<String> ids = new ArrayList<>();
        for (MailStore.StoredMessage message : messages) {
            ids.add(message.collection + "/" + message.id);
        }
        return ids;
    }


    @Test
    public void theCountsReadTheSummaryForTheAttachmentChipAlone() {
        // NOTE: the counts stand an empty summary in for the real one when the
        // chip is off, which holds only while they read nothing else of it.
        Pattern column = Pattern.compile("\\bs\\.([a-z_]+)");
        for (String name : new String[] {"COUNT_MAIL", "COUNT_MAIL_BY_DAY", "COUNT_UNREAD"}) {
            Matcher used = column.matcher(PimdirSql.bind(name, new HashMap<>()).sql);
            while (used.find()) {
                assertTrue(
                        name + " reads s." + used.group(1),
                        Set.of("collection", "link_id", "attachment").contains(used.group(1)));
            }
        }
    }

    @Test
    public void aCountWithNoChipJoinsNoSummary() throws Exception {
        list(inbox, "i", 20, "2026-03-02T00:30:00Z");
        MailStore.Query query = store.query((account, collection) -> true, false, false, "");
        assertEquals(20, store.count(query));
        assertEquals(0, store.count(query.unread()));
        assertEquals(1, store.countByDay(query, "+0 minutes").size());
        MailStore.Query chip = store.query((account, collection) -> true, false, true, "");
        assertEquals(0, store.count(chip));

        String plan =
                plan(MailStore.unjoined(
                        PimdirSql.bind("COUNT_MAIL", query.values(null, -1)), null));
        assertFalse(plan, plan.contains("mail_summary_1"));
    }


    @Test
    public void aStepAsksTheStoreWhichOfAPageItHolds() throws Exception {
        assertFalse(storage.bindsAny(inbox));
        list(inbox, "i", 5, "2026-03-02T00:30:00Z");
        list(archive, "a", 5, "2026-03-02T00:30:00Z");
        assertTrue(storage.bindsAny(inbox));

        MailEngine engine =
                new MailEngine(pimdir, Clients.real(), null, store.accountIdOf(EMAIL));
        assertEquals(
                Set.of("i1", "a4"),
                storage.heldOf(List.of(inbox, archive), List.of("i1", "a4", "new", "i9")));
        assertEquals("none outside a step", Set.of(), engine.held(List.of("i1")));

        JSONObject[] answered = new JSONObject[1];
        MailClient bridge =
                new MailClient() {
                    @Override
                    public NamedStep step(
                            MailSession session,
                            StepRequest request,
                            java.io.File dir,
                            OfflineDriver driver) {
                        assertFalse(
                                "no held list rides the request",
                                Arrays.stream(StepRequest.class.getRecordComponents())
                                        .anyMatch(component -> component.getName().equals("held")));
                        try {
                            answered[0] =
                                    new JSONObject(
                                            driver.serve(
                                                    new JSONObject()
                                                            .put("op", "held")
                                                            .put("handles",
                                                                    new JSONArray().put("i2")
                                                                            .put("a0")
                                                                            .put("gone"))
                                                            .toString()));
                            return new NamedStep(null, false, false, null, List.of());
                        } catch (Exception failure) {
                            throw new AssertionError(failure);
                        }
                    }
                };
        new MailEngine(
                        pimdir,
                        new Clients(
                                new AccountClient(), new ContactClient(), new CalendarClient(),
                                bridge, new OfflineClient()),
                        null,
                        store.accountIdOf(EMAIL))
                .stepRead(
                List.of(inbox),
                new StepRequest(null, 50, null, null, List.of(), null),
                context.getCacheDir());
        JSONArray held = answered[0].getJSONArray("held");
        assertEquals("the step's mailboxes alone", 1, held.length());
        assertEquals("i2", held.getString(0));
    }


    @Test
    public void anOpenedMessageIsStreamedIntoTheStoreAndReadFromIt() throws Exception {
        storage.applyWrites(new JSONArray().put(listed(inbox, "42", "2026-03-02T00:30:00Z")));
        byte[] source =
                ("From: Ada <ada@example.org>\r\nSubject: Opened\r\n"
                                + "Date: Mon, 2 Mar 2026 00:30:00 +0000\r\n"
                                + "Content-Type: multipart/mixed; boundary=b\r\n\r\n"
                                + "--b\r\nContent-Type: text/plain\r\n\r\nHello\r\n"
                                + "--b\r\nContent-Type: application/pdf; name=a.pdf\r\n"
                                + "Content-Disposition: attachment; filename=a.pdf\r\n"
                                + "Content-Transfer-Encoding: base64\r\n\r\nJVBERi0=\r\n--b--\r\n")
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8);
        List<java.io.File> dirs = new ArrayList<>();
        MailClient bridge =
                new MailClient() {
                    @Override
                    public MessageSources fetchSources(
                            MailSession on, String mailbox, List<String> ids, java.io.File dir) {
                        dirs.add(dir);
                        java.io.File file = new java.io.File(dir, "42.eml");
                        try {
                            java.nio.file.Files.write(file.toPath(), source);
                        } catch (java.io.IOException failure) {
                            throw new AssertionError(failure);
                        }
                        return new MessageSources(
                                List.of(),
                                List.of(
                                        new MessageSource(
                                                ids.get(0), null, file.getAbsolutePath(), null,
                                                file.length())));
                    }
                };
        MailStager stager =
                new MailStager(
                        pimdir,
                        new Clients(
                                new AccountClient(), new ContactClient(), new CalendarClient(),
                                bridge, new OfflineClient()));
        MailReader reader = new MailReader(store, new FileStore(context, pimdir), bridge, stager);

        MailStore.StoredMessage message = store.message(inbox, "42");
        MailReader.Read read = reader.read(message, () -> null);

        assertTrue(read.fetched);
        assertEquals("Opened", read.body.subject());
        assertEquals(1, read.body.attachments().size());
        java.io.File stored = store.sourceFile(message);
        assertEquals(
                "the bridge hashes as the store names its blobs",
                PimdirHash.of(source),
                stored.getName());
        assertTrue(java.util.Arrays.equals(source, store.storedSource(inbox, "42")));
        assertFalse("the file moved, its scratch folder went", dirs.get(0).exists());
        assertEquals(
                "the summary restated from the body",
                1,
                scalar("SELECT attachment FROM mail_summary WHERE link_id = '42'"));

        MailReader.Read again = reader.read(store.message(inbox, "42"), () -> null);
        assertFalse(again.fetched);
        assertEquals(1, dirs.size());
    }
}
