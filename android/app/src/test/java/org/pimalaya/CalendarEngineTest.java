package org.pimalaya;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.pimalaya.client.Calendar;
import org.pimalaya.client.Event;
import org.pimalaya.client.PimalayaClient;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

import java.util.List;

/**
 * A calendar reconciled against a listing, through the real bridge and
 * the real store.
 *
 * <p>What these pin is that the agenda survives a sync. It reads entries
 * by their body, and a sync that finds the remote content changed drops
 * the body on purpose and leaves the placement below full: without the
 * hydrate pass beside it, one remote edit empties the calendar and
 * nothing ever puts it back.
 */
@RunWith(RobolectricTestRunner.class)
public class CalendarEngineTest {
    private static final String EMAIL = "jane@example.com";
    private static final String URL = "https://dav.example.com/cal/work/";

    private static final String ICAL =
            "BEGIN:VCALENDAR\r\nVERSION:2.0\r\nBEGIN:VEVENT\r\nUID:ev-1\r\n"
                    + "DTSTART:20260105T090000\r\nSUMMARY:Standup\r\nEND:VEVENT\r\n"
                    + "END:VCALENDAR\r\n";

    private static final String EDITED = ICAL.replace("Standup", "Standup, moved");

    private EventStore events;
    private CalendarEngine engine;
    private String collection;

    @Before
    public void setUp() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        PimdirDb pimdir = new PimdirDb(context);
        events = new EventStore(context, pimdir);
        events.replaceCalendars(
                EMAIL, List.of(new Calendar("work", "Work", URL, null, null)));
        collection = events.loadCalendars().get(0).id;

        // No account: nothing here reaches a server, the listing being the
        // caller's and the fetch a lookup in it.
        engine =
                new CalendarEngine(
                        pimdir,
                        new PimalayaClient(),
                        null,
                        new PimdirAccount(context).idOf(EMAIL));

        // One entry the sync has already reconciled, agreed at "etag-1".
        engine.sync(collection, List.of(new Event("ev-1.ics", "etag-1", ICAL)));
        assertEquals(1, events.loadEvents().size());
    }

    @Test
    public void anEntryTheServerChangedIsRewrittenRatherThanLost() throws Exception {
        engine.sync(collection, List.of(new Event("ev-1.ics", "etag-2", EDITED)));

        List<EventStore.StoredEvent> stored = events.loadEvents();
        assertEquals("the entry is still in the agenda", 1, stored.size());
        assertEquals("carrying what the server now holds", EDITED, stored.get(0).ical);
        assertEquals("and the revision the next write is guarded by", "etag-2", stored.get(0).etag);
    }

    @Test
    public void anUnchangedCalendarIsLeftAsItIs() throws Exception {
        engine.sync(collection, List.of(new Event("ev-1.ics", "etag-1", ICAL)));

        List<EventStore.StoredEvent> stored = events.loadEvents();
        assertEquals(1, stored.size());
        assertEquals(ICAL, stored.get(0).ical);
        assertEquals("etag-1", stored.get(0).etag);
    }

    @Test
    public void anEntryTheServerNoLongerHoldsLeavesTheAgenda() throws Exception {
        engine.sync(collection, List.of());

        assertTrue(events.loadEvents().isEmpty());
    }

    @Test
    public void anEntryTheServerGainedJoinsTheAgenda() throws Exception {
        engine.sync(
                collection,
                List.of(
                        new Event("ev-1.ics", "etag-1", ICAL),
                        new Event("ev-2.ics", "etag-9", EDITED)));

        assertEquals(2, events.loadEvents().size());
    }
}
