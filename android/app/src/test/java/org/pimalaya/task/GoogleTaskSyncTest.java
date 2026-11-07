package org.pimalaya.task;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

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
import org.pimalaya.client.Transport;
import org.pimalaya.pimdir.PimdirAccount;
import org.pimalaya.pimdir.PimdirDb;
import org.pimalaya.pimdir.PimdirStorage;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** A Google task list over the real calendar driver, engine and store. */
@RunWith(RobolectricTestRunner.class)
public class GoogleTaskSyncTest {
    private static final String EMAIL = "jane@gmail.com";
    private static final String LIST = "google://" + EMAIL + "/tasks/list-1";

    private final Google google = new Google();

    private Context context;
    private PimdirDb pimdir;
    private CalendarEngine engine;
    private TaskStore store;
    private TaskWrites writes;
    private EventStore.StoredCalendar list;

    /** One Google task list: Google's copy of each task by its id. */
    private static final class Google extends CalendarClient {
        /** The tasks by id, as (ETag, Google's copy). */
        final Map<String, String[]> tasks = new LinkedHashMap<>();
        /** Whether the next insert lands and its answer is lost. */
        boolean loseNext;
        int inserts;
        int updates;
        private int revisions;

        /** What Google keeps of a VTODO: its title, notes and completion. */
        String kept(String id, String ical) {
            StringBuilder task =
                    new StringBuilder(
                            "BEGIN:VCALENDAR\r\nVERSION:2.0\r\nPRODID:-//Pimalaya//io-gtasks//EN"
                                    + "\r\nBEGIN:VTODO\r\nUID:" + id + "\r\n"
                                    + "DTSTAMP:20261107T100000Z\r\n");
            String status = "NEEDS-ACTION";
            for (String line : ical.replace("\r\n ", "").split("\r\n")) {
                if (line.startsWith("SUMMARY:") || line.startsWith("DESCRIPTION:")) {
                    task.append(line).append("\r\n");
                } else if (line.equals("STATUS:COMPLETED")) {
                    status = "COMPLETED";
                }
            }
            return task.append("STATUS:").append(status).append("\r\n")
                    .append("X-GOOGLE-WEB-VIEW-LINK:https://tasks.google.com/task/")
                    .append(id).append("\r\nEND:VTODO\r\nEND:VCALENDAR\r\n")
                    .toString();
        }

        @Override
        public EventDelta syncEvents(
                Transport transport, Account account, String calendarUrl, String cursor) {
            List<EventRef> changed = new ArrayList<>();
            List<Event> bodies = new ArrayList<>();
            for (Map.Entry<String, String[]> task : tasks.entrySet()) {
                String[] held = task.getValue();
                changed.add(new EventRef(task.getKey(), held[0]));
                bodies.add(new Event(task.getKey(), held[0], held[1]));
            }
            return new EventDelta(changed, bodies, List.of(), "t", cursor == null);
        }

        @Override
        public List<Event> multigetEvents(
                Transport transport, Account account, String calendarUrl, List<String> ids) {
            List<Event> read = new ArrayList<>();
            for (String id : ids) {
                String[] held = tasks.get(id);
                if (held != null) {
                    read.add(new Event(id, held[0], held[1]));
                }
            }
            return read;
        }

        @Override
        public EventRef createEvent(
                Transport transport, Account account, EventTarget target, String ical) {
            inserts += 1;
            String id = "g" + inserts;
            String etag = "e" + ++revisions;
            tasks.put(id, new String[] {etag, kept(id, ical)});
            if (loseNext) {
                loseNext = false;
                throw new PimalayaException("Read timed out");
            }
            return new EventRef(id, etag);
        }

        @Override
        public String updateEvent(
                Transport transport, Account account, EventTarget target, String ical) {
            updates += 1;
            String[] held = tasks.get(target.id());
            if (held == null) {
                throw new PimalayaException("Google answered HTTP 404", 404);
            }
            if (target.etag() != null && !target.etag().equals(held[0])) {
                throw new PimalayaException("Google answered HTTP 412", 412);
            }
            held[0] = "e" + ++revisions;
            held[1] = kept(target.id(), ical);
            return held[0];
        }

        @Override
        public void deleteEvent(Transport transport, Account account, EventTarget target) {
            if (tasks.remove(target.id()) == null) {
                throw new PimalayaException("Google answered HTTP 404", 404);
            }
        }
    }

    @Before
    public void setUp() {
        context = RuntimeEnvironment.getApplication();
        pimdir = new PimdirDb(context);
        EventStore events = new EventStore(context, pimdir);
        Clients clients =
                new Clients(
                        new AccountClient(), new ContactClient(), google, new MailClient(),
                        new OfflineClient());
        Account account = new Account("google://" + EMAIL, EMAIL, "token");
        engine =
                new CalendarEngine(
                        pimdir,
                        clients,
                        new RemoteAccount(null, account, new PimdirAccount(context).idOf(EMAIL)));
        CalendarParts parts = new CalendarParts(pimdir, clients, events);
        store = new TaskStore(parts);
        writes = new TaskWrites(parts);
        events.replaceCalendars(
                EMAIL, List.of(new Calendar("list-1", "My Tasks", LIST, "", null, "", true, true)));
        list = events.loadCalendars().get(0);
    }

    private String one(String sql, String... args) {
        try (Cursor cursor = pimdir.getReadableDatabase().rawQuery(sql, args)) {
            return cursor.moveToFirst() ? cursor.getString(0) : null;
        }
    }

    /** The handles the server source binds the list's live tasks under. */
    private String bound() {
        return one(
                "SELECT group_concat(handle) FROM (SELECT b.handle FROM bindings b"
                        + " JOIN items i ON i.id = b.item WHERE i.collection = ?"
                        + " AND i.deleted = 0 AND b.source = ? ORDER BY b.handle)",
                list.id,
                PimdirStorage.SERVER);
    }

    @Test
    public void aTaskWhoseInsertAnswerWasLostIsFiledOnce() throws Exception {
        engine.sync(list.id);
        writes.capture(list, "Call Ann", "About the gate", null);
        google.loseNext = true;

        assertThrows(PimalayaException.class, () -> engine.sync(list.id));
        assertEquals("the insert reached Google", 1, google.tasks.size());

        engine.sync(list.id);

        assertEquals(1, google.inserts);
        assertEquals(List.of("g1"), new ArrayList<>(google.tasks.keySet()));
        assertEquals("g1", bound());
        List<TaskStore.Stored> tasks = store.load(context);
        assertEquals(1, tasks.size());
        assertEquals(Task.INBOX, tasks.get(0).task.list());
        String body = tasks.get(0).event.ical();
        assertEquals(body.indexOf("BEGIN:VTODO"), body.lastIndexOf("BEGIN:VTODO"));

        engine.sync(list.id);
        assertEquals(1, google.inserts);
        assertEquals("landed as an answered insert", 0, google.updates);
    }

    @Test
    public void aHeldTaskReadingTheSameIsNeverClaimed() throws Exception {
        engine.sync(list.id);
        writes.capture(list, "Call Ann", null, null);
        engine.sync(list.id);
        writes.capture(list, "Call Ann", null, null);
        google.loseNext = true;
        assertThrows(PimalayaException.class, () -> engine.sync(list.id));

        engine.sync(list.id);

        assertEquals(2, google.inserts);
        assertEquals(List.of("g1", "g2"), new ArrayList<>(google.tasks.keySet()));
        assertEquals("g1,g2", bound());
        assertEquals(2, store.load(context).size());
    }
}
