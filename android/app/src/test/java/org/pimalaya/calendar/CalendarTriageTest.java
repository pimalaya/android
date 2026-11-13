package org.pimalaya.calendar;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.pimalaya.client.Calendar;
import org.pimalaya.client.Clients;
import org.pimalaya.pimdir.PimdirAccount;
import org.pimalaya.pimdir.PimdirDb;
import org.pimalaya.pimdir.PimdirHash;
import org.pimalaya.pimdir.PimdirStorage;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

import java.util.List;

/**
 * A calendar pass's triage of conflicted entries, on the real bridge and store.
 *
 * <p>What the merge settles is staged on the binding that conflicted, on either
 * source; what it cannot stays marked for the entry page.
 */
@RunWith(RobolectricTestRunner.class)
public class CalendarTriageTest {
    private static final String EMAIL = "jane@example.com";

    private static final String BASE = entry("Standup", "Room 1", "20260101T000000Z");

    private PimdirDb pimdir;
    private PimdirStorage storage;
    private EventStore events;
    private CalendarEngine engine;

    /** The stored collection id of the one calendar, namespaced by account. */
    private String calendar;

    @Before
    public void setUp() {
        Context context = RuntimeEnvironment.getApplication();
        pimdir = new PimdirDb(context);
        storage = new PimdirStorage(pimdir);
        events = new EventStore(context, pimdir);
        events.replaceCalendars(
                EMAIL,
                List.of(
                        new Calendar(
                                "work", "Work", "https://dav.example.org/cal/", "", null, "",
                                true)));
        calendar = events.loadCalendars().get(0).id;
        engine =
                new CalendarEngine(
                        pimdir,
                        Clients.real(),
                        new RemoteAccount(null, null, new PimdirAccount(context).idOf(EMAIL)));
    }

    /** One entry, as a side writes it at {@code stamp}. */
    private static String entry(String summary, String location, String stamp) {
        return "BEGIN:VCALENDAR\r\nVERSION:2.0\r\nPRODID:-//test//EN\r\nBEGIN:VEVENT\r\n"
                + "UID:e\r\nDTSTAMP:" + stamp + "\r\nLAST-MODIFIED:" + stamp + "\r\n"
                + "DTSTART:20260105T090000\r\nDTEND:20260105T100000\r\n"
                + "SUMMARY:" + summary + "\r\nLOCATION:" + location + "\r\n"
                + "END:VEVENT\r\nEND:VCALENDAR\r\n";
    }

    private static JSONObject storeObject(String body) throws Exception {
        return new JSONObject()
                .put("op", "storeObject")
                .put("hash", PimdirHash.of(body))
                .put("body", body);
    }

    /** The entry placed on one source, its base agreed at {@link #BASE}. */
    private static JSONObject placement(String collection, String handle, String body)
            throws Exception {
        return new JSONObject()
                .put("collection", collection)
                .put("handle", handle)
                .put("linkId", "e.ics")
                .put("object", PimdirHash.of(body))
                .put("sortKey", "")
                .put("level", "full")
                .put("flags", new JSONArray())
                .put("status", "clean")
                .put(
                        "base",
                        new JSONObject()
                                .put("revision", "etag-base")
                                .put("object", PimdirHash.of(BASE)));
    }

    /**
     * The entry edited here into {@code local}, conflicted on a source.
     *
     * <p>The source {@code collection} names, which holds {@code remote}.
     */
    private void conflict(String collection, String handle, String local, String remote)
            throws Exception {
        JSONArray writes =
                new JSONArray()
                        .put(storeObject(BASE))
                        .put(storeObject(local))
                        .put(storeObject(remote));
        if (!collection.equals(calendar)) {
            writes.put(
                    new JSONObject()
                            .put("op", "upsert")
                            .put("placement", placement(calendar, "e.ics", local)));
        }

        JSONObject conflicted = placement(collection, handle, local);
        conflicted.put("status", "conflict");
        conflicted.put("conflictRevision", "etag-remote");
        conflicted.put("conflictObject", PimdirHash.of(remote));
        writes.put(new JSONObject().put("op", "upsert").put("placement", conflicted));
        storage.applyWrites(writes);
    }

    private EventStore.StoredEvent stored() {
        for (EventStore.StoredEvent event : events.loadEvents()) {
            if (event.id.equals("e.ics")) {
                return event;
            }
        }
        throw new AssertionError("the entry is not stored");
    }

    @Test
    public void aMergeWithNothingToAskIsStagedOnTheBindingThatConflicted() throws Exception {
        conflict(
                calendar,
                "e.ics",
                entry("Standup, late", "Room 1", "20260201T100000Z"),
                entry("Standup", "Room 2", "20260201T110000Z"));

        assertEquals(1, engine.triage(calendar));

        assertTrue(EventStore.conflictsOf(storage, calendar).isEmpty());
        String staged = storage.loadRow(calendar, "e.ics").getString("vcard");
        assertTrue(staged, staged.contains("SUMMARY:Standup, late\r\n"));
        assertTrue(staged, staged.contains("LOCATION:Room 2\r\n"));
        assertFalse(stored().conflicted);
    }

    @Test
    public void aCollisionStaysMarkedForTheEntryPage() throws Exception {
        String remote = entry("Retro", "Room 1", "20260201T110000Z");
        conflict(calendar, "e.ics", entry("Planning", "Room 1", "20260201T100000Z"), remote);

        assertEquals(0, engine.triage(calendar));

        EventStore.StoredEvent event = stored();
        assertTrue(event.conflicted);
        EventStore.StoredConflict found = events.conflictOf(event);
        assertNotNull(found);
        assertEquals(calendar, found.collection);
        assertEquals(remote, found.remote);
        assertEquals(BASE, found.base);
    }

    @Test
    public void aPhoneSourceIsTriagedTheSameWay() throws Exception {
        String phone = PimdirStorage.phoneCollection(calendar);
        conflict(
                phone,
                "raw-7",
                entry("Standup, late", "Room 1", "20260201T100000Z"),
                entry("Standup", "Room 2", "20260201T110000Z"));
        assertTrue(stored().conflicted);
        assertEquals(phone, events.conflictOf(stored()).collection);

        assertEquals(1, engine.triage(phone));

        assertTrue(EventStore.conflictsOf(storage, phone).isEmpty());
        assertFalse(stored().conflicted);
    }

    /**
     * A delete settles a conflict only as a resolution naming the version it
     * was shown (pimdir SYNC §7): one naming none is refused, nothing staged.
     */
    @Test
    public void aConflictedEntryDeletedNamingNoConflictIsRefused() throws Exception {
        conflict(
                calendar,
                "e.ics",
                entry("Planning", "Room 1", "20260201T100000Z"),
                entry("Retro", "Room 1", "20260201T110000Z"));

        try {
            engine.mutateRemove(calendar, "e.ics");
            org.junit.Assert.fail("a delete naming no conflict settled one");
        } catch (org.pimalaya.client.PimalayaException refused) {
            assertTrue(refused.getMessage(), refused.getMessage().contains("conflict"));
        }

        assertFalse(storage.loadRow(calendar, "e.ics").getBoolean("deleted"));
        assertFalse(EventStore.conflictsOf(storage, calendar).isEmpty());
    }

    @Test
    public void aConflictedEntryDeletedWholeIsDeletedAgainstWhatTheSourceWasSeenToHold()
            throws Exception {
        conflict(
                calendar,
                "e.ics",
                entry("Planning", "Room 1", "20260201T100000Z"),
                entry("Retro", "Room 1", "20260201T110000Z"));

        engine.mutateRemove(calendar, "e.ics", null, events.conflictOf(stored()).resolves);

        // NOTE: the delete is staged against the revision recorded when the
        // edit there was seen, never the stale base, so a later edit there
        // still refuses it.
        JSONObject row = storage.loadRow(calendar, "e.ics");
        assertTrue(row.getBoolean("deleted"));
        assertEquals("etag-remote", row.getString("etag"));
        assertTrue(EventStore.conflictsOf(storage, calendar).isEmpty());
    }

    /** One source's pull of {@code body} at {@code revision}, agreed there. */
    private static JSONObject pulled(String collection, String handle, String body,
            String revision) throws Exception {
        JSONObject placement = placement(collection, handle, body);
        placement.getJSONObject("base").put("revision", revision).put("object", PimdirHash.of(body));
        return new JSONObject().put("op", "upsert").put("placement", placement);
    }

    /**
     * An entry the phone and the server changed apart, the item keeping the
     * phone's and recording the server's (SYNC §9), is marked in the agenda
     * and triaged on the server like a source's conflict: the body the server
     * last agreed is the ancestor, and the merge settles it for both sources.
     */
    @Test
    public void anEntryTwoSourcesDivergedOnIsTriagedOnTheServer() throws Exception {
        String phone = PimdirStorage.phoneCollection(calendar);
        String local = entry("Standup, late", "Room 1", "20260201T100000Z");
        String remote = entry("Standup", "Room 2", "20260201T110000Z");
        storage.applyWrites(new JSONArray().put(storeObject(BASE))
                .put(pulled(calendar, "e.ics", BASE, "etag-base")));
        storage.applyWrites(new JSONArray().put(pulled(phone, "raw-7", BASE, "v1")));
        storage.applyWrites(new JSONArray().put(storeObject(local))
                .put(pulled(phone, "raw-7", local, "v2")));
        storage.applyWrites(new JSONArray().put(storeObject(remote))
                .put(pulled(calendar, "e.ics", remote, "etag-remote")));

        assertTrue(stored().conflicted);
        EventStore.StoredConflict found = events.conflictOf(stored());
        assertEquals(calendar, found.collection);
        assertEquals(BASE, found.base);
        assertEquals(local, found.local);
        assertEquals(remote, found.remote);

        assertEquals(1, engine.triage(calendar));

        assertFalse(stored().conflicted);
        String staged = storage.loadRow(calendar, "e.ics").getString("vcard");
        assertTrue(staged, staged.contains("SUMMARY:Standup, late\r\n"));
        assertTrue(staged, staged.contains("LOCATION:Room 2\r\n"));
    }
}
