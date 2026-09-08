package org.pimalaya;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.pimalaya.client.Calendar;
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
    private PimdirItems items;
    private EventStore store;

    /** The stored collection id of {@link #CALENDAR}, namespaced by account. */
    private String collection;

    @Before
    public void setUp() {
        Context context = RuntimeEnvironment.getApplication();
        pimdir = new PimdirDb(context);
        items = new PimdirItems(pimdir);
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

    /** Files one object the way a reconcile files it: the text and its ETag. */
    private void event(String id, String etag) {
        SQLiteDatabase db = items.writable();
        db.beginTransaction();
        try {
            items.put(db, collection, new PimdirItems.Row(id, ICAL, null, "", "[]", etag));
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    @Test
    public void anEventRoundTripsAsTheTextTheServerSent() {
        event("ev-1", "etag-1");

        List<EventStore.StoredEvent> events = store.loadEvents();
        assertEquals(1, events.size());
        assertEquals(collection, events.get(0).collectionId);
        assertEquals("ev-1", events.get(0).id);
        assertEquals("byte for byte, so the expansion sees what the server sent",
                ICAL, events.get(0).ical);
        assertEquals("the validator the next write is guarded by", "etag-1", events.get(0).etag);
        assertEquals("bound, so the handle is the resource name", "ev-1", events.get(0).handle);
    }

    @Test
    public void anEntryTheServerHasNeverSeenCarriesItsProvisionalHandle() {
        // A staged create has no binding, so nothing has named it yet: the
        // engine addresses it by the handle its identity derives (SYNC §2),
        // and a page opened on it has to hand back that same handle or the
        // edit would name a resource nobody knows.
        SQLiteDatabase db = items.writable();
        db.beginTransaction();
        try {
            items.put(db, collection, new PimdirItems.Row("new.ics", ICAL, null, ""));
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }

        EventStore.StoredEvent staged = store.loadEvents().get(0);
        assertEquals(PimdirStorage.provisionalOf("new.ics"), staged.handle);
        assertEquals("", staged.etag);
    }

    @Test
    public void aVanishedCalendarTakesItsEventsWithIt() {
        event("ev-1", "etag-1");

        store.replaceCalendars(EMAIL, List.of());

        assertTrue(store.loadCalendars().isEmpty());
        assertTrue("the items cascade with their collection", store.loadEvents().isEmpty());
    }
}
