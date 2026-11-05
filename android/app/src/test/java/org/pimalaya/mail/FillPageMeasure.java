package org.pimalaya.mail;

import static org.junit.Assert.assertEquals;
import static org.junit.Assume.assumeTrue;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.pimalaya.client.Flags;
import org.pimalaya.client.Mailbox;
import org.pimalaya.pimdir.PimdirDb;
import org.pimalaya.pimdir.PimdirStorage;
import org.pimalaya.pimdir.PimdirSummary;
import org.pimalaya.pimdir.StoreProfile;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * A fill page's write, timed on the host (io-pimdir's
 * cairn/changes/a-page-lands-in-few-statements): pages of new listed
 * messages through {@link PimdirStorage#applyWrites}, with the schema as it
 * is and with its stamp triggers dropped, each page profiled by statement.
 * Run by hand, with {@code PIMALAYA_MEASURE} set; the platform's classes over
 * SQLite 3.18.2. The numbers compare the two; only a device gives absolutes.
 */
@RunWith(RobolectricTestRunner.class)
public class FillPageMeasure {
    private static final String EMAIL = "jane@example.com";

    /** A Graph or JMAP fill page; IMAP's is the same order. */
    private static final int PAGE = 500;

    /** The pages timed after the first, which compiles the statements. */
    private static final int PAGES = 5;

    /** The triggers drawing the item stamps (STORAGE §4.5). */
    private static final List<String> STAMPS =
            List.of(
                    "items_stamp_insert",
                    "items_stamp_update",
                    "items_stamp_request",
                    "placement_flag_stamp_insert");

    @Test
    public void aFillPageWithTheStampTriggers() throws Exception {
        assumeTrue(System.getenv("PIMALAYA_MEASURE") != null);
        fill("stamped", false);
    }

    @Test
    public void aFillPageWithoutTheStampTriggers() throws Exception {
        assumeTrue(System.getenv("PIMALAYA_MEASURE") != null);
        fill("unstamped", true);
    }

    private static void fill(String label, boolean unstamped) throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        PimdirDb pimdir = new PimdirDb(context);
        PimdirStorage storage = new PimdirStorage(pimdir);
        MailStore store = new MailStore(context, pimdir);
        store.replaceMailboxes(EMAIL, List.of(new Mailbox("INBOX", "inbox")));
        String inbox = store.collectionOf(EMAIL, "INBOX");
        SQLiteDatabase db = pimdir.getWritableDatabase();
        if (unstamped) {
            for (String trigger : STAMPS) {
                db.execSQL("DROP TRIGGER " + trigger);
            }
        }

        Instant top = Instant.parse("2026-10-01T12:00:00Z");
        StringBuilder pages = new StringBuilder();
        StoreProfile last = null;
        for (int round = 0; round <= PAGES; round++) {
            JSONArray writes = new JSONArray();
            for (int index = 0; index < PAGE; index++) {
                int at = round * PAGE + index;
                writes.put(listed(inbox, "m" + at, top.minusSeconds(60L * at).toString()));
            }
            long began = System.nanoTime();
            StoreProfile.begin();
            try {
                storage.applyWrites(writes);
            } finally {
                last = StoreProfile.end();
            }
            long spent = (System.nanoTime() - began) / 1_000_000;
            pages.append(round == 0 ? " first " : " ").append(spent).append(" ms");
        }
        assertEquals((PAGES + 1) * PAGE, count(db, "SELECT count(*) FROM items"));

        System.out.println(
                "fill page " + label + ": " + PAGE + " messages, pages" + pages);
        System.out.println("fill page " + label + ", last page " + last);
        for (Map.Entry<String, long[]> entry : last.byName()) {
            System.out.printf(
                    "fill page %s   %-32s %5d x %7.2f ms%n",
                    label, entry.getKey(), entry.getValue()[1], entry.getValue()[0] / 1e6);
        }
    }

    /**
     * One new listed message as a Graph or JMAP page brings it: read and
     * flagged, a summary with a sender and a recipient, bound and based.
     */
    private static JSONObject listed(String collection, String handle, String date)
            throws Exception {
        JSONArray flags =
                new JSONArray().put(Flags.Marker.SEEN.wire()).put(Flags.Marker.FLAGGED.wire());
        JSONObject placement = new JSONObject();
        placement.put("collection", collection);
        placement.put("handle", handle);
        placement.put("linkId", handle);
        placement.put("level", "meta");
        placement.put("status", "clean");
        placement.put("flags", flags);
        placement.put(
                "summary",
                PimdirSummary.mail(
                        "<" + handle + "@example.org>", "Message " + handle, "Sender",
                        "sender@example.org", EMAIL, date, 2048, false));
        placement.put("sortKey", PimdirSummary.mailSortKey(date));
        placement.put("base", new JSONObject().put("flags", new JSONArray(flags.toString())));
        return new JSONObject().put("op", "upsert").put("placement", placement);
    }

    private static long count(SQLiteDatabase db, String sql) {
        try (Cursor cursor = db.rawQuery(sql, null)) {
            return cursor.moveToFirst() ? cursor.getLong(0) : -1;
        }
    }
}
