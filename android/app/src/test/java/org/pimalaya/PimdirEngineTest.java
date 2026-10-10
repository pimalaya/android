package org.pimalaya;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.pimalaya.client.PimalayaClient;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The half of a driver every domain shares, over a remote that answers
 * from a fixture instead of a server.
 *
 * <p>What it pins is the pass, not the protocol: a sync that finds the
 * remote content changed drops the body on purpose and leaves the
 * placement below full, and something has to put it back. Nothing else
 * does, so a domain whose listing reads by body empties itself on the
 * first remote edit if this is missing. It was, for calendars, and the
 * agenda went blank.
 */
@RunWith(RobolectricTestRunner.class)
public class PimdirEngineTest {
    private static final String COLLECTION = "acct/Work";

    private static final String BODY = "BEGIN:VCALENDAR\r\nUID:ev-1\r\nEND:VCALENDAR\r\n";
    private static final String EDITED = BODY.replace("ev-1", "ev-1-moved");

    private PimdirDb pimdir;
    private PimdirItems items;
    private Remote engine;

    /** A driver whose remote is a map of handle to (revision, body). */
    private static final class Remote extends PimdirEngine {
        final Map<String, String[]> members = new LinkedHashMap<>();

        /** Every handle the merge asked to read, in order, across rounds. */
        final List<String> fetched = new ArrayList<>();

        Remote(PimdirDb pimdir, PimalayaClient client) {
            super(pimdir, client);
        }

        void sync(String collection) {
            client.offlineSync(this, collection, false);
            hydrate(collection);
        }

        @Override
        protected PimDomain domain() {
            return PimDomain.CALENDAR;
        }

        @Override
        protected JSONObject enumerate(JSONObject yielded) throws JSONException {
            JSONArray listed = new JSONArray();
            for (Map.Entry<String, String[]> member : members.entrySet()) {
                listed.put(
                        new JSONObject()
                                .put("handle", member.getKey())
                                .put("revision", member.getValue()[0]));
            }

            JSONObject reply = new JSONObject();
            reply.put("items", listed);
            reply.put("vanished", new JSONArray());
            reply.put("complete", true);
            return reply;
        }

        @Override
        protected JSONObject fetch(JSONObject yielded) throws JSONException {
            JSONArray read = new JSONArray();
            for (String handle : stringsOf(yielded.getJSONArray("handles"))) {
                fetched.add(handle);
                String[] member = members.get(handle);
                if (member == null) {
                    continue;
                }
                read.put(
                        new JSONObject()
                                .put("handle", handle)
                                .put("linkId", handle)
                                .put("hash", PimdirHash.of(member[1]))
                                .put("body", member[1])
                                .put("sortKey", "")
                                .put("revision", member[0]));
            }

            JSONObject reply = new JSONObject();
            reply.put("items", read);
            return reply;
        }

        @Override
        protected JSONObject push(JSONObject yielded) throws JSONException {
            JSONArray results = new JSONArray();
            JSONArray changes = yielded.getJSONArray("changes");
            for (int index = 0; index < changes.length(); index++) {
                results.put(result(changes.getJSONObject(index).getString("handle"), true, null, null));
            }

            JSONObject reply = new JSONObject();
            reply.put("results", results);
            return reply;
        }
    }

    @Before
    public void setUp() {
        Context context = RuntimeEnvironment.getApplication();
        pimdir = new PimdirDb(context);
        items = new PimdirItems(pimdir);
        pimdir.getWritableDatabase()
                .execSQL(
                        "INSERT INTO collections(id, account, kind, name)"
                                + " VALUES('acct/Work', 'acct', 'text/calendar', 'Work')");

        engine = new Remote(pimdir, new PimalayaClient());
        engine.members.put("ev-1.ics", new String[] {"etag-1", BODY});
        engine.sync(COLLECTION);
    }

    /** The bodies the store holds, by handle. */
    private Map<String, String> stored() {
        Map<String, String> bodies = new LinkedHashMap<>();
        try (android.database.Cursor cursor =
                items.readable()
                        .rawQuery(
                                "SELECT link_id, object_hash FROM items"
                                        + " WHERE collection = ? AND deleted = 0"
                                        + " AND retained_at IS NULL AND object_hash IS NOT NULL",
                                new String[] {COLLECTION})) {
            while (cursor.moveToNext()) {
                bodies.put(cursor.getString(0), items.body(cursor.getString(1)));
            }
        }
        return bodies;
    }

    @Test
    public void aFirstPassReadsWhatItEnumerated() {
        assertEquals(Map.of("ev-1.ics", BODY), stored());
    }

    @Test
    public void aMemberTheRemoteChangedIsReadAgainRatherThanLost() {
        engine.fetched.clear();
        engine.members.put("ev-1.ics", new String[] {"etag-2", EDITED});
        engine.sync(COLLECTION);

        // The merge drops the body when the revision moves, on purpose;
        // the hydrate beside it is the only thing that puts one back.
        assertEquals(Map.of("ev-1.ics", EDITED), stored());
        assertEquals(List.of("ev-1.ics"), engine.fetched);
    }

    @Test
    public void aQuietPassReadsNothing() {
        engine.fetched.clear();
        engine.sync(COLLECTION);

        // Nothing moved, so nothing is read: the whole point of asking
        // what changed rather than asking for everything.
        assertTrue(engine.fetched.isEmpty());
        assertEquals(Map.of("ev-1.ics", BODY), stored());
    }

    @Test
    public void aMemberTheRemoteNoLongerHoldsLeaves() {
        engine.members.clear();
        engine.sync(COLLECTION);

        assertTrue(stored().isEmpty());
    }

    @Test
    public void aDownloadCountsWhatHasBeenRead() {
        for (int index = 0; index < 130; index++) {
            String handle = "new-" + index + ".ics";
            engine.members.put(handle, new String[] {"etag-1", BODY.replace("ev-1", handle)});
        }
        List<String> told = new ArrayList<>();
        engine.progress =
                new PimdirEngine.Progress() {
                    @Override
                    public void step(PimDomain domain, int stage, int count) {
                        told.add("step " + stage + " " + count);
                    }

                    @Override
                    public void advance(PimDomain domain, int stage, int done, int total) {
                        told.add(stage + ": " + done + " of " + total);
                    }
                };
        engine.sync(COLLECTION);

        // NOTE: the naming read goes 64 bodies at a time, and the bar
        // follows the reads that landed, never ahead of them.
        int download = PimdirEngine.Progress.STAGE_DOWNLOAD;
        assertEquals(
                List.of(
                        "step " + download + " 130",
                        download + ": 64 of 130",
                        download + ": 128 of 130",
                        download + ": 130 of 130"),
                told);
    }
}
