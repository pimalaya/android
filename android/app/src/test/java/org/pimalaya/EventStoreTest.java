package org.pimalaya;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.database.Cursor;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.pimalaya.client.Calendar;
import org.pimalaya.client.Event;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

import java.util.List;

/**
 * The calendar mirror, on the shared store.
 *
 * <p>An event is stored as the iCalendar text the server sent, because what it
 * renders as depends on the window being shown; these check that the text
 * survives the round trip through the blob directory and that a re-listing
 * leaves nothing behind.
 */
@RunWith(RobolectricTestRunner.class)
public class EventStoreTest {
    private static final String EMAIL = "jane@example.com";
    private static final String OTHER = "john@example.org";
    private static final String CALENDAR = "https://dav.example.com/cal/work/";

    private static final String ICAL =
            "BEGIN:VCALENDAR\r\nVERSION:2.0\r\nBEGIN:VEVENT\r\nUID:ev-1\r\n"
                    + "DTSTART:20260105T090000\r\nSUMMARY:Standup\r\nEND:VEVENT\r\n"
                    + "END:VCALENDAR\r\n";

    private PimdirDb pimdir;
    private EventStore store;

    /** The stored collection id of {@link #CALENDAR}, namespaced by account. */
    private String collection;

    @Before
    public void setUp() {
        Context context = RuntimeEnvironment.getApplication();
        pimdir = new PimdirDb(context);
        store = new EventStore(context, pimdir);
        store.replaceCalendars(
                EMAIL, List.of(new Calendar("work", "Work", CALENDAR, "Team calendar", "#ff0000")));
        collection = store.loadCalendars().get(0).id;
    }

    private long scalar(String sql, String... args) {
        try (Cursor cursor = pimdir.getReadableDatabase().rawQuery(sql, args)) {
            return cursor.moveToFirst() ? cursor.getLong(0) : -1;
        }
    }

    @Test
    public void aCalendarIsACollectionOfItsOwnKind() {
        assertEquals(
                1, scalar("SELECT count(*) FROM collections WHERE kind = ?", PimdirSummary.CALENDAR));

        List<EventStore.StoredCalendar> calendars = store.loadCalendars();
        assertEquals(1, calendars.size());
        assertEquals(CALENDAR, calendars.get(0).url);
        assertEquals("Work", calendars.get(0).name);
        assertEquals("#ff0000", calendars.get(0).color);
        assertEquals(EMAIL, calendars.get(0).accountEmail);
    }

    @Test
    public void twoAccountsSharingACalendarIdDoNotCollide() {
        // The JMAP address carries its account id now, so two accounts on one
        // provider no longer hand identical URLs down. This pins the store's
        // own guard against it anyway: a backend that did would be one
        // collection that each sync round re-points at the other account,
        // merging two people's agendas.
        String jmapUrl = "jmap://api.example.com/c1";
        store.replaceCalendars(
                EMAIL, List.of(new Calendar("c1", "Mine", jmapUrl, null, null)));
        store.replaceCalendars(
                OTHER, List.of(new Calendar("c1", "Theirs", jmapUrl, null, null)));

        List<EventStore.StoredCalendar> calendars = store.loadCalendars();
        assertEquals(2, calendars.size());
        assertNotEquals(calendars.get(0).id, calendars.get(1).id);

        // And the address the listing round asks for is the un-namespaced one.
        for (EventStore.StoredCalendar calendar : calendars) {
            assertEquals(jmapUrl, calendar.url);
        }
    }

    @Test
    public void anEventRoundTripsAsTheTextTheServerSent() {
        store.replaceEvents(collection, List.of(new Event("ev-1", "etag-1", ICAL)));

        List<EventStore.StoredEvent> events = store.loadEvents();
        assertEquals(1, events.size());
        assertEquals(collection, events.get(0).collectionId);
        assertEquals("ev-1", events.get(0).id);
        assertEquals("byte for byte, so the expansion sees what the server sent",
                ICAL, events.get(0).ical);
    }

    @Test
    public void aRefreshDropsWhatTheServerNoLongerHas() {
        store.replaceEvents(
                collection,
                List.of(new Event("ev-1", "etag-1", ICAL), new Event("ev-2", "etag-2", ICAL)));
        assertEquals(2, store.loadEvents().size());

        store.replaceEvents(collection, List.of(new Event("ev-1", "etag-1", ICAL)));

        assertEquals(1, store.loadEvents().size());
        // The dropped event's body had no other reader, so it is collected
        // rather than left pinned in the object directory forever.
        assertEquals(1, scalar("SELECT count(*) FROM objects"));
    }

    @Test
    public void aVanishedCalendarTakesItsEventsWithIt() {
        store.replaceEvents(collection, List.of(new Event("ev-1", "etag-1", ICAL)));

        store.replaceCalendars(EMAIL, List.of());

        assertTrue(store.loadCalendars().isEmpty());
        assertTrue("the items cascade with their collection", store.loadEvents().isEmpty());
    }
}
