package org.pimalaya.calendar;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.pimalaya.client.Account;
import org.pimalaya.client.AccountClient;
import org.pimalaya.client.Calendar;
import org.pimalaya.client.CalendarClient;
import org.pimalaya.client.Clients;
import org.pimalaya.client.ContactClient;
import org.pimalaya.client.Event;
import org.pimalaya.client.EventDelta;
import org.pimalaya.client.EventRef;
import org.pimalaya.client.EventTarget;
import org.pimalaya.client.Flags;
import org.pimalaya.client.MailClient;
import org.pimalaya.client.OfflineClient;
import org.pimalaya.client.PimalayaException;
import org.pimalaya.client.Transport;
import org.pimalaya.pimdir.PimdirAccount;
import org.pimalaya.pimdir.PimdirDb;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * An entry created and edited on the phone, then deleted, over a CalDAV
 * server answering its PUTs with no ETag, as Google's does: the real
 * calendar driver, engine and store over a fake server.
 */
@RunWith(RobolectricTestRunner.class)
public class CalendarDavWriteTest {
    private static final String EMAIL = "dav-write@example.com";
    private static final String URL = "https://dav.example.org/cal/";

    private final Account account = new Account("https://dav.example.org/", EMAIL, "secret");

    private EventStore events;
    private CalendarEngine engine;
    private String calendar;
    private final Dav dav = new Dav();

    /** A CalDAV calendar listed whole on every round, answering no ETag to a PUT. */
    private static final class Dav extends CalendarClient {
        /** The entries by resource name, as (ETag, body). */
        final Map<String, String[]> entries = new LinkedHashMap<>();
        final List<String> deleted = new ArrayList<>();
        int revisions;

        @Override
        public EventDelta syncEvents(
                Transport transport, Account account, String calendarUrl, String cursor) {
            List<EventRef> listed = new ArrayList<>();
            for (Map.Entry<String, String[]> entry : entries.entrySet()) {
                listed.add(new EventRef(entry.getKey(), entry.getValue()[0]));
            }
            return new EventDelta(listed, List.of(), List.of(), null, true);
        }

        @Override
        public List<Event> multigetEvents(
                Transport transport, Account account, String calendarUrl, List<String> ids) {
            List<Event> read = new ArrayList<>();
            for (String id : ids) {
                String[] entry = entries.get(id);
                if (entry != null) {
                    read.add(new Event(id, entry[0], entry[1]));
                }
            }
            return read;
        }

        @Override
        public EventRef createEvent(
                Transport transport, Account account, EventTarget target, String ical) {
            String id = target.id();
            entries.put(id, new String[] {"e" + ++revisions, ical});
            return new EventRef(id, null);
        }

        @Override
        public String updateEvent(
                Transport transport, Account account, EventTarget target, String ical) {
            String id = target.id();
            String[] entry = entries.get(id);
            if (entry == null) {
                throw new PimalayaException("not found: " + id, 404);
            }
            entry[0] = "e" + ++revisions;
            entry[1] = ical;
            return null;
        }

        @Override
        public void deleteEvent(Transport transport, Account account, EventTarget target) {
            String id = target.id();
            if (entries.remove(id) == null) {
                throw new PimalayaException("not found: " + id, 404);
            }
            deleted.add(id);
        }
    }

    @Before
    public void setUp() {
        Context context = RuntimeEnvironment.getApplication();
        PimdirDb pimdir = new PimdirDb(context);
        events = new EventStore(context, pimdir);
        events.replaceCalendars(EMAIL, List.of(new Calendar("work", "Work", URL, "", null)));
        calendar = events.loadCalendars().get(0).id;
        engine =
                new CalendarEngine(
                        pimdir,
                        new Clients(
                                new AccountClient(), new ContactClient(), dav, new MailClient(),
                                new OfflineClient()),
                        null,
                        account,
                        new PimdirAccount(context).idOf(EMAIL),
                        null);
    }

    private static String ical(String summary) {
        return "BEGIN:VCALENDAR\r\nVERSION:2.0\r\nPRODID:-//test//EN\r\nBEGIN:VEVENT\r\n"
                + "UID:e-1\r\nDTSTAMP:20261011T090000Z\r\nDTSTART:20261013T090000Z\r\n"
                + "DTEND:20261013T100000Z\r\nSUMMARY:" + summary + "\r\nEND:VEVENT\r\n"
                + "END:VCALENDAR\r\n";
    }

    private EventStore.StoredEvent stored() {
        for (EventStore.StoredEvent event : events.loadEvents()) {
            if (event.collectionId.equals(calendar)) {
                return event;
            }
        }
        throw new AssertionError("no entry stored");
    }

    private boolean anyStored() {
        for (EventStore.StoredEvent event : events.loadEvents()) {
            if (event.collectionId.equals(calendar)) {
                return true;
            }
        }
        return false;
    }

    @Test
    public void anEntryCreatedEditedThenDeletedIsDeletedOnAServerAnsweringNoEtag()
            throws Exception {
        engine.mutateAdd(calendar, "e-1.ics", ical("Created"), Flags.none(), null, "");
        engine.sync(calendar);
        assertEquals(List.of("e-1.ics"), new ArrayList<>(dav.entries.keySet()));

        engine.mutateEdit(calendar, stored().handle, ical("Edited"), null, "");
        engine.sync(calendar);
        assertTrue(dav.entries.get("e-1.ics")[1].contains("SUMMARY:Edited"));

        engine.mutateRemove(calendar, stored().handle);
        engine.sync(calendar);

        assertEquals(List.of("e-1.ics"), dav.deleted);
        engine.sync(calendar);
        assertTrue("not pulled back", !anyStored());
    }
}
