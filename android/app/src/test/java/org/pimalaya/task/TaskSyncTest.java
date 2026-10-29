package org.pimalaya.task;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.database.Cursor;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.pimalaya.calendar.CalendarEngine;
import org.pimalaya.calendar.CalendarParts;
import org.pimalaya.calendar.EventStore;
import org.pimalaya.calendar.RemoteAccount;
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
import org.pimalaya.client.MailClient;
import org.pimalaya.client.OfflineClient;
import org.pimalaya.client.PimalayaException;
import org.pimalaya.client.Task;
import org.pimalaya.client.TaskEdit;
import org.pimalaya.client.Transport;
import org.pimalaya.pimdir.PimdirAccount;
import org.pimalaya.pimdir.PimdirDb;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The Tasks tab over a CalDAV server: summaries as listed, a capture filed
 * elsewhere superseding its source, and calendars refusing tasks
 * (cairn/changes/gtd-hardening).
 *
 * <p>The real calendar driver, engine, bridge and store over a fake server.
 */
@RunWith(RobolectricTestRunner.class)
public class TaskSyncTest {
    private static final String EMAIL = "tasks@example.com";
    private static final String WORK = "https://dav.example.org/cal/work/";
    private static final String HOME = "https://dav.example.org/cal/home/";

    private final Account account = new Account("https://dav.example.org/", EMAIL, "secret");
    private final Dav dav = new Dav();

    private Context context;
    private PimdirDb pimdir;
    private EventStore events;
    private CalendarEngine engine;
    private TaskStore store;
    private TaskWrites writes;

    /** A CalDAV home listed whole each round, by calendar URL. */
    private static final class Dav extends CalendarClient {
        /** The entries of each calendar by resource name, as (ETag, body). */
        final Map<String, Map<String, String[]>> calendars = new LinkedHashMap<>();
        /** The calendars answering a create with 403, as Fastmail does. */
        final Set<String> refusing = new HashSet<>();
        int revisions;

        Map<String, String[]> of(String url) {
            return calendars.computeIfAbsent(url, any -> new LinkedHashMap<>());
        }

        @Override
        public EventDelta syncEvents(
                Transport transport, Account account, String calendarUrl, String cursor) {
            List<EventRef> listed = new ArrayList<>();
            for (Map.Entry<String, String[]> entry : of(calendarUrl).entrySet()) {
                listed.add(new EventRef(entry.getKey(), entry.getValue()[0]));
            }
            return new EventDelta(listed, List.of(), List.of(), null, true);
        }

        @Override
        public List<Event> multigetEvents(
                Transport transport, Account account, String calendarUrl, List<String> ids) {
            List<Event> read = new ArrayList<>();
            for (String id : ids) {
                String[] entry = of(calendarUrl).get(id);
                if (entry != null) {
                    read.add(new Event(id, entry[0], entry[1]));
                }
            }
            return read;
        }

        @Override
        public EventRef createEvent(
                Transport transport, Account account, EventTarget target, String ical) {
            if (refusing.contains(target.calendarUrl())) {
                throw new PimalayaException(
                        "WebDAV collection already holds a resource with the same UID", 403);
            }
            String etag = "e" + ++revisions;
            of(target.calendarUrl()).put(target.id(), new String[] {etag, ical});
            return new EventRef(target.id(), etag);
        }

        @Override
        public String updateEvent(
                Transport transport, Account account, EventTarget target, String ical) {
            String[] entry = of(target.calendarUrl()).get(target.id());
            if (entry == null) {
                throw new PimalayaException("not found: " + target.id(), 404);
            }
            entry[0] = "e" + ++revisions;
            entry[1] = ical;
            return entry[0];
        }

        @Override
        public void deleteEvent(Transport transport, Account account, EventTarget target) {
            if (of(target.calendarUrl()).remove(target.id()) == null) {
                throw new PimalayaException("not found: " + target.id(), 404);
            }
        }
    }

    @Before
    public void setUp() {
        context = RuntimeEnvironment.getApplication();
        pimdir = new PimdirDb(context);
        events = new EventStore(context, pimdir);
        Clients clients =
                new Clients(
                        new AccountClient(), new ContactClient(), dav, new MailClient(),
                        new OfflineClient());
        engine =
                new CalendarEngine(
                        pimdir,
                        clients,
                        new RemoteAccount(null, account, new PimdirAccount(context).idOf(EMAIL)));
        CalendarParts parts = new CalendarParts(pimdir, clients, events);
        store = new TaskStore(parts);
        writes = new TaskWrites(parts);
    }

    private void list(Calendar... calendars) {
        events.replaceCalendars(EMAIL, List.of(calendars));
    }

    private EventStore.StoredCalendar calendar(String name) {
        for (EventStore.StoredCalendar calendar : events.loadCalendars()) {
            if (calendar.name.equals(name)) {
                return calendar;
            }
        }
        throw new AssertionError("no calendar " + name);
    }

    private static String todo(String uid, String extra) {
        return "BEGIN:VCALENDAR\r\nVERSION:2.0\r\nPRODID:-//other//EN\r\nBEGIN:VTODO\r\n"
                + "UID:" + uid + "\r\nDTSTAMP:20261020T080000Z\r\nSUMMARY:Fix the gate\r\n"
                + extra + "END:VTODO\r\nEND:VCALENDAR\r\n";
    }

    private String one(String sql, String... args) {
        try (Cursor cursor = pimdir.getReadableDatabase().rawQuery(sql, args)) {
            return cursor.moveToFirst() ? cursor.getString(0) : null;
        }
    }

    @Test
    public void aListedToDoCarriesItsSummary() {
        list(new Calendar("work", "Work", WORK, "", null, "", true));
        dav.of(WORK).put("gate.ics", new String[] {"e0", todo("gate", "CATEGORIES:waiting,@home\r\nPRIORITY:2\r\n")});

        engine.sync(calendar("Work").id);

        // NOTE: what CalendarEngine listed with no summary before, the row a
        // reader lists tasks from (task_summary, Annex A.4).
        assertEquals("[\"waiting\",\"@home\"]", one("SELECT categories FROM task_summary"));
        assertEquals("2", one("SELECT priority FROM task_summary"));
        assertEquals("Fix the gate", one("SELECT summary FROM task_summary"));
    }

    @Test
    public void aCaptureFiledElsewhereLeavesItsSourceUntilTheAddIsPushed() throws Exception {
        list(
                new Calendar("work", "Work", WORK, "", null, "", true),
                new Calendar("home", "Home", HOME, "", null, "", true));
        dav.of(WORK).put("gate.ics", new String[] {"e0", todo("gate", "CATEGORIES:inbox\r\n")});
        engine.sync(calendar("Work").id);
        engine.sync(calendar("Home").id);
        TaskStore.Stored captured = store.load(context).get(0);
        dav.refusing.add(HOME);

        writes.moveTo(
                captured, calendar("Home"), TaskEdit.at(TaskWrites.now()).withList(Task.NEXT));
        // NOTE: the source's calendar first, as a pass may sync it first: the
        // tombstone is held while the add superseding it is a pending create.
        engine.sync(calendar("Work").id);
        assertThrows(PimalayaException.class, () -> engine.sync(calendar("Home").id));
        engine.sync(calendar("Work").id);

        assertTrue("the capture stays on its server", dav.of(WORK).containsKey("gate.ics"));
        assertTrue(dav.of(HOME).isEmpty());
        List<TaskStore.Stored> tasks = store.load(context);
        assertEquals(1, tasks.size());
        assertEquals("Home", tasks.get(0).calendar.name);
    }

    @Test
    public void aCaptureFiledElsewhereMovesOnceTheAddIsPushed() throws Exception {
        list(
                new Calendar("work", "Work", WORK, "", null, "", true),
                new Calendar("home", "Home", HOME, "", null, "", true));
        dav.of(WORK).put("gate.ics", new String[] {"e0", todo("gate", "CATEGORIES:inbox\r\n")});
        engine.sync(calendar("Work").id);
        engine.sync(calendar("Home").id);
        TaskStore.Stored captured = store.load(context).get(0);

        writes.moveTo(
                captured, calendar("Home"), TaskEdit.at(TaskWrites.now()).withList(Task.NEXT));
        engine.sync(calendar("Work").id);
        assertTrue("held until the add is pushed", dav.of(WORK).containsKey("gate.ics"));
        engine.sync(calendar("Home").id);
        engine.sync(calendar("Work").id);

        assertFalse(dav.of(WORK).containsKey("gate.ics"));
        // NOTE: under the source's name, its UID and its edit.
        String[] filed = dav.of(HOME).get("gate.ics");
        assertTrue(filed[1].contains("UID:gate\r\n"));
        assertFalse(filed[1].contains("inbox"));
        assertEquals(1, store.load(context).size());
    }

    @Test
    public void aCalendarStatingNoToDoTakesNoCapture() throws Exception {
        list(
                new Calendar("work", "Work", WORK, "", null, "", true, true),
                new Calendar("events", "Events", HOME, "", null, "", true, false));
        engine.sync(calendar("Work").id);
        engine.sync(calendar("Events").id);

        List<EventStore.StoredCalendar> targets = store.targets();

        // NOTE: Google's CalDAV calendars state VEVENT alone and answer a
        // VTODO PUT with 403 (x-vtodo none, pimdir STORAGE §15.6).
        assertEquals(1, targets.size());
        assertEquals("Work", targets.get(0).name);
        assertThrows(
                PimalayaException.class,
                () -> writes.capture(calendar("Events"), "Call Ann", null, null));
    }
}
