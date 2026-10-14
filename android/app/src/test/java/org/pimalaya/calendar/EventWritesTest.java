package org.pimalaya.calendar;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.pimalaya.client.AccountClient;
import org.pimalaya.client.Calendar;
import org.pimalaya.client.CalendarClient;
import org.pimalaya.client.Clients;
import org.pimalaya.client.ContactClient;
import org.pimalaya.client.DateEdit;
import org.pimalaya.client.EventEdit;
import org.pimalaya.client.EventScope;
import org.pimalaya.client.EventSides;
import org.pimalaya.client.Flags;
import org.pimalaya.client.MailClient;
import org.pimalaya.client.Mutation;
import org.pimalaya.client.OfflineClient;
import org.pimalaya.client.OfflineDriver;
import org.pimalaya.pimdir.PimdirDb;
import org.pimalaya.pimdir.PimdirHash;
import org.pimalaya.pimdir.PimdirStorage;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * What the entry page's save and delete stage, on the real bridge and
 * store: the writes, their order and the binding each lands on.
 */
@RunWith(RobolectricTestRunner.class)
public class EventWritesTest {
    private static final String EMAIL = "jane@example.com";
    private static final String STAMP = "20260102T000000Z";

    /** A weekly series of six Mondays, from 5 January 2026. */
    private static final String SERIES =
            "BEGIN:VCALENDAR\r\nVERSION:2.0\r\nPRODID:-//test//EN\r\nBEGIN:VEVENT\r\n"
                    + "UID:s\r\nDTSTAMP:20260101T000000Z\r\n"
                    + "DTSTART:20260105T090000\r\nDTEND:20260105T100000\r\n"
                    + "SUMMARY:Standup\r\nRRULE:FREQ=WEEKLY;COUNT=6\r\n"
                    + "END:VEVENT\r\nEND:VCALENDAR\r\n";

    private static final String BASE = entry("Standup", "20260101T000000Z");

    /** The bridge, every staged mutation recorded before it goes through. */
    private static final class Recording extends OfflineClient {
        final List<String> staged = new ArrayList<>();
        final List<String> bodies = new ArrayList<>();

        @Override
        public void mutate(OfflineDriver driver, String collection, Mutation mutation) {
            String key = mutation.linkId() != null ? mutation.linkId() : mutation.handle();
            staged.add(mutation.op().wire() + " " + collection + " " + key);
            bodies.add(Objects.toString(mutation.body(), ""));
            super.mutate(driver, collection, mutation);
        }
    }

    private final Recording client = new Recording();

    private PimdirStorage storage;
    private EventStore events;
    private CalendarStager stager;
    private EventWrites writes;
    private EventStore.StoredCalendar calendar;

    @Before
    public void setUp() {
        Context context = RuntimeEnvironment.getApplication();
        PimdirDb pimdir = new PimdirDb(context);
        storage = new PimdirStorage(pimdir);
        events = new EventStore(context, pimdir);
        events.replaceCalendars(
                EMAIL,
                List.of(new Calendar("work", "Work", "https://dav.example.org/cal/", "", null)));
        calendar = events.loadCalendars().get(0);
        stager =
                new CalendarStager(
                        pimdir,
                        new Clients(
                                new AccountClient(), new ContactClient(), new CalendarClient(),
                                new MailClient(), client));
        writes = new EventWrites(stager);
    }

    /** One entry under UID {@code e}, as a side writes it at {@code stamp}. */
    private static String entry(String summary, String stamp) {
        return "BEGIN:VCALENDAR\r\nVERSION:2.0\r\nPRODID:-//test//EN\r\nBEGIN:VEVENT\r\n"
                + "UID:e\r\nDTSTAMP:" + stamp + "\r\nLAST-MODIFIED:" + stamp + "\r\n"
                + "DTSTART:20260105T090000\r\nDTEND:20260105T100000\r\n"
                + "SUMMARY:" + summary + "\r\nEND:VEVENT\r\nEND:VCALENDAR\r\n";
    }

    /** The series staged as a create, the recording cleared after it. */
    private EventStore.StoredEvent series() throws Exception {
        stager.mutateAdd(calendar.id, "s.ics", SERIES, Flags.none(), null, "");
        client.staged.clear();
        client.bodies.clear();
        return stored("s.ics");
    }

    private EventStore.StoredEvent stored(String id) {
        for (EventStore.StoredEvent event : events.loadEvents()) {
            if (event.id.equals(id)) {
                return event;
            }
        }
        throw new AssertionError("not stored: " + id);
    }

    private static JSONObject storeObject(String body) throws Exception {
        return new JSONObject()
                .put("op", "storeObject")
                .put("hash", PimdirHash.of(body))
                .put("body", body);
    }

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
     * The entry edited here into {@code local} and on the calendar's phone
     * source into {@code remote}, conflicted there; answers that conflict.
     */
    private EventStore.StoredConflict phoneConflict(String local, String remote)
            throws Exception {
        String phone = PimdirStorage.phoneCollection(calendar.id);
        JSONObject conflicted = placement(phone, "raw-7", local);
        conflicted.put("status", "conflict");
        conflicted.put("conflictRevision", "etag-remote");
        conflicted.put("conflictObject", PimdirHash.of(remote));
        storage.applyWrites(
                new JSONArray()
                        .put(storeObject(BASE))
                        .put(storeObject(local))
                        .put(storeObject(remote))
                        .put(
                                new JSONObject()
                                        .put("op", "upsert")
                                        .put("placement", placement(calendar.id, "e.ics", local)))
                        .put(new JSONObject().put("op", "upsert").put("placement", conflicted)));
        EventStore.StoredConflict conflict = events.conflictOf(stored("e.ics"));
        assertEquals(phone, conflict.collection);
        return conflict;
    }

    @Test
    public void followingStagesTheNewSeriesThenTheShortenedOne() throws Exception {
        EventStore.StoredEvent target = series();
        EventEdit edited =
                EventEdit.of(
                                Map.of(),
                                Map.of(
                                        EventEdit.DateField.START,
                                        new DateEdit("20260119T100000", null, null),
                                        EventEdit.DateField.END,
                                        new DateEdit("20260119T110000", null, null)),
                                null,
                                STAMP)
                        .scoped(EventScope.FOLLOWING, "20260119T090000", null, "s-2");

        writes.save(
                new EventWrites.Save(
                        EventScope.FOLLOWING, edited, "s-2", target, calendar, false, true, null,
                        Map.of()));

        assertEquals(
                List.of(
                        "add " + calendar.id + " s-2.ics",
                        "edit " + calendar.id + " " + target.handle),
                client.staged);
        assertTrue(stored("s-2.ics").ical.contains("UID:s-2\r\n"));
        assertTrue(stored("s.ics").ical.contains("UNTIL="));
    }

    @Test
    public void aNewEntryIsStagedAsAnAddUnderItsId() throws Exception {
        String id = "n.ics";
        EventStore.StoredEvent target =
                new EventStore.StoredEvent(
                        calendar.id,
                        id,
                        PimdirStorage.rowHandle(null, id),
                        entry("New", "20260101T000000Z"),
                        "");
        EventEdit edited =
                EventEdit.of(Map.of(EventEdit.Field.SUMMARY, "Lunch"), Map.of(), null, STAMP)
                        .scoped(EventScope.ALL, null, null, null);

        writes.save(
                new EventWrites.Save(
                        EventScope.ALL, edited, "", target, calendar, true, true, null,
                        Map.of()));

        assertEquals(List.of("add " + calendar.id + " " + id), client.staged);
        assertTrue(stored(id).ical.contains("SUMMARY:Lunch\r\n"));
    }

    @Test
    public void aResolutionWithNothingEditedIsStagedAsResolvedOnTheConflictedBinding()
            throws Exception {
        EventStore.StoredConflict settling =
                phoneConflict(
                        entry("Planning", "20260201T100000Z"),
                        entry("Retro", "20260201T110000Z"));
        String resolved =
                CalendarClient.resolveEvent(
                        new EventSides(settling.base, settling.local, settling.remote), Map.of());
        EventEdit edited =
                EventEdit.of(Map.of(), Map.of(), null, STAMP)
                        .scoped(EventScope.ALL, null, null, null);

        writes.save(
                new EventWrites.Save(
                        EventScope.ALL, edited, "", stored("e.ics"), calendar, false, false,
                        settling, Map.of()));

        assertEquals(List.of("edit " + settling.collection + " raw-7"), client.staged);
        assertEquals(resolved, client.bodies.get(0));
        assertTrue(EventStore.conflictsOf(storage, settling.collection).isEmpty());
    }

    @Test
    public void deletingOneOccurrenceStagesAnEditOfTheSeries() throws Exception {
        EventStore.StoredEvent target = series();
        EventEdit edit =
                EventEdit.of(Map.of(), Map.of(), null, STAMP)
                        .scoped(EventScope.THIS, "20260119T090000", null, null);

        writes.delete(
                new EventWrites.Delete(EventScope.THIS, edit, target, calendar, null, Map.of()));

        assertEquals(List.of("edit " + calendar.id + " " + target.handle), client.staged);
        assertTrue(stored("s.ics").ical.contains("EXDATE"));
    }

    @Test
    public void deletingAllRemovesTheEntry() throws Exception {
        EventStore.StoredEvent target = series();
        EventEdit edit =
                EventEdit.of(Map.of(), Map.of(), null, STAMP)
                        .scoped(EventScope.ALL, null, null, null);

        writes.delete(
                new EventWrites.Delete(EventScope.ALL, edit, target, calendar, null, Map.of()));

        assertEquals(List.of("remove " + calendar.id + " " + target.handle), client.staged);
    }

    @Test
    public void deletingAllWhileSettlingRemovesTheConflictedBinding() throws Exception {
        EventStore.StoredConflict settling =
                phoneConflict(
                        entry("Planning", "20260201T100000Z"),
                        entry("Retro", "20260201T110000Z"));
        EventEdit edit =
                EventEdit.of(Map.of(), Map.of(), null, STAMP)
                        .scoped(EventScope.ALL, null, null, null);

        writes.delete(
                new EventWrites.Delete(
                        EventScope.ALL,
                        edit,
                        stored("e.ics"),
                        calendar,
                        settling,
                        Map.of()));

        assertEquals(List.of("remove " + settling.collection + " raw-7"), client.staged);
        assertTrue(EventStore.conflictsOf(storage, settling.collection).isEmpty());
    }
}
