package org.pimalaya.task;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.pimalaya.calendar.CalendarParts;
import org.pimalaya.calendar.CalendarStager;
import org.pimalaya.calendar.EventStore;
import org.pimalaya.client.AccountClient;
import org.pimalaya.client.Calendar;
import org.pimalaya.client.CalendarClient;
import org.pimalaya.client.Clients;
import org.pimalaya.client.ContactClient;
import org.pimalaya.client.Flags;
import org.pimalaya.client.MailClient;
import org.pimalaya.client.OfflineClient;
import org.pimalaya.client.Task;
import org.pimalaya.client.TaskEdit;
import org.pimalaya.pimdir.PimdirDb;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

import java.util.List;

/**
 * What the Tasks tab writes and reads back, on the real bridge and store.
 *
 * <p>A capture, a clarification and a close, each a staged VTODO the next
 * load reads the GTD way, the state a reserved category
 * (cairn/changes/gtd-by-categories).
 */
@RunWith(RobolectricTestRunner.class)
public class TaskWritesTest {
    private static final String EMAIL = "jane@example.com";

    private Context context;
    private TaskStore store;
    private TaskWrites writes;
    private CalendarParts parts;
    private EventStore.StoredCalendar calendar;

    @Before
    public void setUp() {
        context = RuntimeEnvironment.getApplication();
        PimdirDb pimdir = new PimdirDb(context);
        EventStore events = new EventStore(context, pimdir);
        events.replaceCalendars(
                EMAIL,
                List.of(
                        new Calendar(
                                "work", "Work", "https://dav.example.org/cal/", "", null, "",
                                true),
                        new Calendar(
                                "shared", "Shared", "https://dav.example.org/shared/", "", null,
                                "", false),
                        new Calendar(
                                "graph", "Graph", "msgraph://jane@example.com/c1", "", null, "",
                                true)));
        for (EventStore.StoredCalendar stored : events.loadCalendars()) {
            if (stored.name.equals("Work")) {
                calendar = stored;
            }
        }
        parts =
                new CalendarParts(
                        pimdir,
                        new Clients(
                                new AccountClient(), new ContactClient(), new CalendarClient(),
                                new MailClient(), new OfflineClient()),
                        events);
        store = new TaskStore(parts);
        writes = new TaskWrites(parts);
    }

    @Test
    public void aCaptureLandsInTheInboxDatelessWithItsMail() throws Exception {
        writes.capture(calendar, "Call the plumber", "About the boiler", "abc@example.org");

        List<TaskStore.Stored> tasks = store.load(context);

        assertEquals(1, tasks.size());
        Task task = tasks.get(0).task;
        assertEquals(Task.INBOX, task.list());
        assertEquals("Call the plumber", task.summary());
        assertEquals("About the boiler", task.description());
        assertEquals("abc@example.org", task.source());
        // NOTE: a capture is placed on no day until it is clarified, so the
        // agenda does not show it.
        assertNull(task.start());
        assertNull(task.due());
        assertTrue(tasks.get(0).event.ical.contains("CATEGORIES:inbox\r\n"));
        assertFalse(tasks.get(0).event.ical.contains("X-PIMALAYA-GTD"));
    }

    @Test
    public void aNewTaskWithNoStateIsANextActionWithNoCategories() throws Exception {
        writes.add(calendar, "Buy ink", "", "abc@example.org", Task.NEXT, List.of());

        TaskStore.Stored added = store.load(context).get(0);

        assertTrue(added.task.in(Task.NEXT));
        assertEquals("abc@example.org", added.task.source());
        assertFalse(added.event.ical.contains("CATEGORIES"));
        assertTrue(added.event.ical.contains("\r\nCREATED:"));
    }

    @Test
    public void aNewTaskCarriesItsStateAndCategories() throws Exception {
        writes.add(calendar, "Call back", null, null, Task.WAITING, List.of("Work", "calls"));

        TaskStore.Stored added = store.load(context).get(0);

        assertTrue(added.task.in(Task.WAITING));
        assertEquals(List.of("Work", "calls"), added.task.categories());
        // NOTE: the categories a line, the state a line of its own after it,
        // where pimdir GUIDE §17 places a new state.
        assertTrue(added.event.ical.contains("CATEGORIES:Work,calls\r\nCATEGORIES:waiting\r\n"));
    }

    @Test
    public void aWaitingItemTickedAndReopenedIsWaitingAgain() throws Exception {
        writes.add(calendar, "Quote from the roofer", null, null, Task.WAITING, List.of());
        writes.edit(store.load(context).get(0), TaskEdit.at(TaskWrites.now()).withDone(true));
        TaskStore.Stored done = store.load(context).get(0);
        assertTrue(TaskOrder.lists(TaskOrder.DONE, Task.NEXT, done.task));

        writes.edit(done, TaskEdit.at(TaskWrites.now()).withDone(false));

        TaskStore.Stored reopened = store.load(context).get(0);
        assertTrue(TaskOrder.lists(null, Task.WAITING, reopened.task));
        assertTrue(reopened.event.ical.contains("\r\nSTATUS:NEEDS-ACTION\r\n"));
        assertFalse(reopened.event.ical.contains("\r\nCOMPLETED:"));
        assertFalse(reopened.event.ical.contains("PERCENT-COMPLETE"));
    }

    @Test
    public void aTaskUnderWayCarriesInProcess() throws Exception {
        writes.add(calendar, "Paint the fence", null, null, Task.NEXT, List.of());
        writes.edit(
                store.load(context).get(0),
                TaskEdit.at(TaskWrites.now()).withStatus(TaskOrder.PROGRESS));

        TaskStore.Stored started = store.load(context).get(0);

        assertTrue(TaskOrder.lists(TaskOrder.PROGRESS, Task.NEXT, started.task));
        assertTrue(TaskOrder.lists(null, Task.NEXT, started.task));
        assertTrue(started.event.ical.contains("\r\nSTATUS:IN-PROCESS\r\n"));
    }

    @Test
    public void clarifyingMovesItAndClosingTakesItOutOfTheLists() throws Exception {
        writes.capture(calendar, "Feedback on the filter page", null, null);
        TaskStore.Stored captured = store.load(context).get(0);

        writes.edit(
                captured,
                TaskEdit.at(TaskWrites.now())
                        .withList(Task.WAITING)
                        .withContact("Léa Martin", "lea@example.net"));
        TaskStore.Stored waiting = store.load(context).get(0);
        assertEquals(Task.WAITING, waiting.task.list());
        assertEquals("lea@example.net", waiting.task.contactAddress());
        assertFalse(waiting.task.closed());

        writes.edit(waiting, TaskEdit.at(TaskWrites.now()).withDone(true));
        Task done = store.load(context).get(0).task;
        assertTrue(done.closed());
        assertEquals("COMPLETED", done.status());
        assertTrue(done.completed() != null);
    }

    @Test
    public void aNextActionCarriesNoStateAndKeepsItsCategories() throws Exception {
        writes.capture(calendar, "Buy ink", null, null);
        TaskStore.Stored captured = store.load(context).get(0);

        writes.edit(
                captured,
                TaskEdit.at(TaskWrites.now())
                        .withList(Task.NEXT)
                        .withCategories("@errands,@home"));
        TaskStore.Stored next = store.load(context).get(0);

        assertEquals(Task.NEXT, next.task.list());
        assertEquals(List.of("@errands", "@home"), next.task.categories());
        assertTrue(next.task.in(Task.NEXT));
        // NOTE: a next action is an open to-do with none of the states, so
        // another client's tasks read as next actions too.
        assertFalse(next.event.ical.toLowerCase(java.util.Locale.ROOT).contains("inbox"));
    }

    @Test
    public void anotherClientsStateIsReadAndOnlyItMoves() throws Exception {
        new CalendarStager(parts.pimdir(), parts.clients())
                .mutateAdd(
                        calendar.id,
                        "other.ics",
                        "BEGIN:VCALENDAR\r\nVERSION:2.0\r\nPRODID:-//other//EN\r\n"
                                + "BEGIN:VTODO\r\nUID:other\r\nSUMMARY:Ask Sam\r\n"
                                + "CATEGORIES:@home,Waiting\r\nX-OTHER:keep\r\n"
                                + "END:VTODO\r\nEND:VCALENDAR\r\n",
                        Flags.none(),
                        null,
                        "");
        TaskStore.Stored waiting = store.load(context).get(0);
        assertTrue(waiting.task.in(Task.WAITING));
        assertEquals(List.of("@home"), waiting.task.categories());

        writes.edit(waiting, TaskEdit.at(TaskWrites.now()).withList(Task.NEXT));
        TaskStore.Stored next = store.load(context).get(0);

        assertTrue(next.task.in(Task.NEXT));
        assertTrue(next.event.ical.contains("CATEGORIES:@home\r\nX-OTHER:keep\r\n"));
    }

    @Test
    public void aCaptureFiledInAnotherCalendarIsThereOnly() throws Exception {
        EventStore events = parts.events();
        events.replaceCalendars(
                EMAIL,
                List.of(
                        new Calendar(
                                "work", "Work", "https://dav.example.org/cal/", "", null, "",
                                true),
                        new Calendar(
                                "home", "Home", "https://dav.example.org/home/", "", null, "",
                                true)));
        EventStore.StoredCalendar home = null;
        for (EventStore.StoredCalendar stored : events.loadCalendars()) {
            if (stored.name.equals("Work")) {
                calendar = stored;
            } else if (stored.name.equals("Home")) {
                home = stored;
            }
        }
        writes.capture(calendar, "Fix the gate", null, null);
        TaskStore.Stored captured = store.load(context).get(0);

        writes.moveTo(captured, home, TaskEdit.at(TaskWrites.now()).withList(Task.NEXT));
        List<TaskStore.Stored> tasks = store.load(context);

        assertEquals(1, tasks.size());
        assertEquals("Home", tasks.get(0).calendar.name);
        assertEquals(captured.task.uid(), tasks.get(0).task.uid());
        assertTrue(tasks.get(0).task.in(Task.NEXT));
    }

    @Test
    public void aDeletedCaptureLeavesTheLists() throws Exception {
        writes.capture(calendar, "Not worth it", null, null);

        writes.remove(store.load(context).get(0));

        assertTrue(store.load(context).isEmpty());
    }

    @Test
    public void onlyWritableCalDavCalendarsTakeTasks() {
        List<EventStore.StoredCalendar> targets = store.targets();

        assertEquals(1, targets.size());
        assertEquals("Work", targets.get(0).name);
    }

    @Test
    public void capturesGoToTheCalendarLastPicked() {
        EventStore events = new EventStore(context, new PimdirDb(context));
        events.replaceCalendars(
                EMAIL,
                List.of(
                        new Calendar(
                                "work", "Work", "https://dav.example.org/cal/", "", null, "",
                                true),
                        new Calendar(
                                "home", "Home", "https://dav.example.org/home/", "", null, "",
                                true)));
        String home = null;
        for (EventStore.StoredCalendar stored : events.loadCalendars()) {
            if (stored.name.equals("Home")) {
                home = stored.id;
            }
        }

        TaskStore.captureTo(context, home);

        assertEquals("Home", store.targets(context).get(0).name);
        assertEquals(2, store.targets(context).size());
    }

    @Test
    public void anAgendaToDoReadsAsItsTask() throws Exception {
        writes.capture(calendar, "Renew the passport", null, null);
        TaskStore.Stored loaded = store.load(context).get(0);

        TaskStore.Stored read = TaskStore.read(loaded.event, loaded.calendar);

        assertEquals("Renew the passport", read.task.summary());
        assertEquals(loaded.task.uid(), read.task.uid());
    }

    /** The first value of a query on the store, null for no row. */
    private String one(String sql) {
        try (android.database.Cursor cursor =
                parts.pimdir().getReadableDatabase().rawQuery(sql, null)) {
            return cursor.moveToFirst() ? cursor.getString(0) : null;
        }
    }

    @Test
    public void aCaptureCarriesItsSummaryAndWhenItWasCaptured() throws Exception {
        writes.capture(calendar, "Call the plumber", null, null);

        TaskStore.Stored captured = store.load(context).get(0);

        // NOTE: staged with the summary its body derives, so the store lists
        // it as a task (task_summary), and CREATED says when (GUIDE §17).
        assertEquals("[\"inbox\"]", one("SELECT categories FROM task_summary"));
        assertEquals("Call the plumber", one("SELECT summary FROM task_summary"));
        assertTrue(captured.event.ical.contains("CREATED:"));
        assertEquals(captured.task.created().length(), 16);
    }

    @Test
    public void anEditTheTaskAlreadyHoldsStagesNothing() throws Exception {
        writes.capture(calendar, "Call the plumber", null, null);
        TaskStore.Stored captured = store.load(context).get(0);

        writes.edit(
                captured,
                TaskEdit.at("20991231T000000Z")
                        .withList(Task.INBOX)
                        .withText("Call the plumber", null));

        // NOTE: no stamp moves either (pimdir GUIDE §17): nothing to push.
        assertEquals(captured.event.ical, store.load(context).get(0).event.ical);
    }

    @Test
    public void aStoreFromBeforeSummariesListsItsTasksAndGainsTheirRows() throws Exception {
        writes.capture(calendar, "Renew the passport", null, null);
        new CalendarStager(parts.pimdir(), parts.clients())
                .mutateAdd(
                        calendar.id,
                        "meeting.ics",
                        "BEGIN:VCALENDAR\r\nVERSION:2.0\r\nPRODID:-//other//EN\r\n"
                                + "BEGIN:VEVENT\r\nUID:meeting\r\nDTSTAMP:20261020T080000Z\r\n"
                                + "DTSTART:20261030T090000Z\r\nSUMMARY:Standup\r\n"
                                + "END:VEVENT\r\nEND:VCALENDAR\r\n",
                        Flags.none(),
                        null,
                        "");
        // NOTE: as a store an earlier build wrote, no calendar item carrying
        // a summary row.
        parts.pimdir().getWritableDatabase().execSQL("DELETE FROM task_summary");
        parts.pimdir().getWritableDatabase().execSQL("DELETE FROM event_summary");

        List<TaskStore.Stored> tasks = store.load(context);

        assertEquals(1, tasks.size());
        assertEquals("Renew the passport", tasks.get(0).task.summary());
        assertEquals("Renew the passport", one("SELECT summary FROM task_summary"));
        assertEquals("Standup", one("SELECT summary FROM event_summary"));
    }

    @Test
    public void theCaptureTimeIsReadFromItsStamp() {
        Task task =
                new Task(
                        "u", "", "", "", Task.WAITING, List.of("waiting"), List.of(), null, 0,
                        null, null, null, "", "", null, "20261020T093000Z");

        assertEquals(1792488600000L, TaskStore.capturedAt(task));
    }
}
