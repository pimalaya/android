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
import org.pimalaya.calendar.EventStore;
import org.pimalaya.client.AccountClient;
import org.pimalaya.client.Calendar;
import org.pimalaya.client.CalendarClient;
import org.pimalaya.client.Clients;
import org.pimalaya.client.ContactClient;
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
 * load reads the GTD way (cairn/changes/gtd).
 */
@RunWith(RobolectricTestRunner.class)
public class TaskWritesTest {
    private static final String EMAIL = "jane@example.com";

    private Context context;
    private TaskStore store;
    private TaskWrites writes;
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
        CalendarParts parts =
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
        assertTrue(tasks.get(0).event.ical.contains("X-PIMALAYA-GTD:INBOX"));
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
    public void aNextActionCarriesNoListAndKeepsItsContexts() throws Exception {
        writes.capture(calendar, "Buy ink", null, null);
        TaskStore.Stored captured = store.load(context).get(0);

        writes.edit(
                captured,
                TaskEdit.at(TaskWrites.now()).withList(Task.NEXT).withContexts("@errands,@home"));
        TaskStore.Stored next = store.load(context).get(0);

        assertEquals(Task.NEXT, next.task.list());
        assertEquals(List.of("@errands", "@home"), next.task.contexts());
        // NOTE: a next action is what a to-do with no list is, so another
        // client's tasks read as next actions too.
        assertFalse(next.event.ical.contains("X-PIMALAYA-GTD"));
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

    @Test
    public void theCaptureTimeIsReadFromItsStamp() {
        Task task =
                new Task(
                        "u", "", "", "", Task.WAITING, List.of(), null, 0, null, null, null, "",
                        "", null, "20261020T093000Z");

        assertEquals(1792488600000L, TaskStore.capturedAt(task));
    }
}
