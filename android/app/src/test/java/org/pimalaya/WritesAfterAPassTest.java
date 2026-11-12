package org.pimalaya;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.app.AlertDialog;
import android.content.Context;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.TextView;

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
import org.pimalaya.client.DateEdit;
import org.pimalaya.client.Event;
import org.pimalaya.client.EventDelta;
import org.pimalaya.client.EventDetail;
import org.pimalaya.client.EventRef;
import org.pimalaya.client.EventTarget;
import org.pimalaya.client.EventTimeKind;
import org.pimalaya.client.MailClient;
import org.pimalaya.client.Occurrence;
import org.pimalaya.client.OfflineClient;
import org.pimalaya.client.Task;
import org.pimalaya.client.TaskEdit;
import org.pimalaya.client.Transport;
import org.pimalaya.pimdir.PimdirAccount;
import org.pimalaya.pimdir.PimdirDb;
import org.pimalaya.task.TaskStore;
import org.pimalaya.task.TaskWrites;
import org.pimalaya.task.TasksList;
import org.pimalaya.ui.Panels;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.shadows.ShadowAlertDialog;
import org.robolectric.shadows.ShadowLog;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * A task page and an entry page left open while a calendar pass runs, then
 * saved: the write reaches the item the pass left, and never lays the body
 * the page read over a change the pass brought in.
 */
@RunWith(RobolectricTestRunner.class)
public class WritesAfterAPassTest {
    private static final String EMAIL = "jane@gmail.com";
    private static final String LIST = "google://" + EMAIL + "/tasks/list-1";
    private static final String DAV = "https://dav.example.com/cal/jane/";

    /** An entry as the CalDAV server first holds it. */
    private static final String LUNCH =
            "BEGIN:VCALENDAR\r\nVERSION:2.0\r\nPRODID:-//test//EN\r\nBEGIN:VEVENT\r\n"
                    + "UID:lunch\r\nDTSTAMP:20261101T090000Z\r\n"
                    + "DTSTART:20261120T120000Z\r\nDTEND:20261120T130000Z\r\n"
                    + "SUMMARY:Lunch\r\nEND:VEVENT\r\nEND:VCALENDAR\r\n";

    /** The app's screen, its syncs counted rather than run. */
    public static class PagesHost extends MainActivity {
        @Override
        void syncCalendars() {}

        @Override
        void syncContacts() {}

        @Override
        void syncAll() {}
    }

    /** One calendar or task list, filing each create under an id of its own. */
    private static final class Server extends CalendarClient {
        /** The members by id, as (ETag, the server's copy). */
        final Map<String, String[]> items = new LinkedHashMap<>();

        @Override
        public EventDelta syncEvents(
                Transport transport, Account account, String calendarUrl, String cursor) {
            List<EventRef> changed = new ArrayList<>();
            List<Event> bodies = new ArrayList<>();
            for (Map.Entry<String, String[]> item : items.entrySet()) {
                changed.add(new EventRef(item.getKey(), item.getValue()[0]));
                bodies.add(new Event(item.getKey(), item.getValue()[0], item.getValue()[1]));
            }
            return new EventDelta(changed, bodies, List.of(), "t", cursor == null);
        }

        @Override
        public List<Event> multigetEvents(
                Transport transport, Account account, String calendarUrl, List<String> ids) {
            List<Event> read = new ArrayList<>();
            for (String id : ids) {
                String[] held = items.get(id);
                if (held != null) {
                    read.add(new Event(id, held[0], held[1]));
                }
            }
            return read;
        }

        @Override
        public EventRef createEvent(
                Transport transport, Account account, EventTarget target, String ical) {
            String id = "g" + (items.size() + 1);
            items.put(id, new String[] {"e1", ical});
            return new EventRef(id, "e1");
        }
    }

    private Context context;
    private PagesHost host;
    private PimdirDb pimdir;
    private Server server;
    private Clients clients;
    private EventStore events;

    @Before
    public void setUp() {
        FakeAndroidKeyStore.install();
        context = RuntimeEnvironment.getApplication();
        host = Robolectric.buildActivity(PagesHost.class).create().get();
        pimdir = App.of(context).pimdir();
        server = new Server();
        clients =
                new Clients(
                        new AccountClient(), new ContactClient(), server, new MailClient(),
                        new OfflineClient());
        events = new EventStore(context, pimdir);
    }

    @Test
    public void aTaskPageSavedAfterThePassFilingItSaves() throws Exception {
        EventStore.StoredCalendar list = taskList();
        CalendarParts parts = new CalendarParts(pimdir, clients, events);
        new TaskWrites(parts).add(list, "Call Ann", null, null, Task.NEXT, List.of());
        host.taskPage.open(new TaskStore(parts).load(context).get(0));
        settle();

        sync(list, "google://" + EMAIL);
        assertEquals(List.of("g1"), new ArrayList<>(server.items.keySet()));
        type(R.id.task_page, R.string.event_field_summary, "Call Ann today");
        host.taskPage.save();
        settle();

        assertEquals(
                "the page's save lands on the task the pass filed, not "
                        + failure("task save failed"),
                "Call Ann today",
                new TaskStore(parts).load(context).get(0).full().summary());
    }

    @Test
    public void aTaskPageSavedAfterThePassChangedItKeepsBoth() throws Exception {
        EventStore.StoredCalendar list = taskList();
        CalendarParts parts = new CalendarParts(pimdir, clients, events);
        new TaskWrites(parts).add(list, "Call Ann", null, null, Task.NEXT, List.of());
        sync(list, "google://" + EMAIL);
        host.taskPage.open(new TaskStore(parts).load(context).get(0));
        settle();

        // NOTE: notes set on Google while the page is open, then synced.
        String[] held = server.items.get("g1");
        server.items.put(
                "g1",
                new String[] {
                    "e2",
                    CalendarClient.writeTask(
                            held[1],
                            TaskEdit.at("20261111T100000Z").withText("Call Ann", "From Google"))
                });
        sync(list, "google://" + EMAIL);
        type(R.id.task_page, R.string.event_field_summary, "Call Ann today");
        host.taskPage.save();
        settle();

        Task stored = new TaskStore(parts).load(context).get(0).full();
        assertEquals("the notes the pass brought survive the save", "From Google",
                stored.description());
        assertEquals("the title typed lands beside them, merged", "Call Ann today",
                stored.summary());
        assertEquals("and the page goes back", Panels.TASKS, host.screen);
    }

    @Test
    public void anEntrySavedAfterThePassChangedItKeepsBoth() throws Exception {
        events.replaceCalendars(
                EMAIL, List.of(new Calendar("cal", "Home", DAV, "", null, "", true)));
        EventStore.StoredCalendar calendar = events.loadCalendars().get(0);
        server.items.put("lunch.ics", new String[] {"e1", LUNCH});
        sync(calendar, "https://dav.example.com/");
        host.eventView.open(null, events.loadEvents().get(0), calendar);
        settle();

        // NOTE: a location set on the server while the page is open.
        server.items.put(
                "lunch.ics",
                new String[] {"e2", LUNCH.replace("SUMMARY:Lunch\r\n",
                        "SUMMARY:Lunch\r\nLOCATION:Room 4\r\n")});
        sync(calendar, "https://dav.example.com/");
        type(R.id.event_view_page, R.string.event_field_summary, "Team lunch");
        host.eventView.save();
        settle();

        EventDetail stored = CalendarClient.readEvent(events.loadEvents().get(0).ical(), null);
        assertEquals(
                "the location the pass brought survives the save, "
                        + failure("event save failed"),
                "Room 4",
                stored.location());
        assertEquals("the title typed lands beside it, merged", "Team lunch", stored.summary());
        assertEquals("and the page goes back", Panels.CALENDAR, host.screen);
    }

    @Test
    public void aNoteBothSidesChangedIsAskedNotOverwritten() throws Exception {
        EventStore.StoredCalendar list = taskList();
        CalendarParts parts = new CalendarParts(pimdir, clients, events);
        new TaskWrites(parts).add(list, "Call Ann", "Base", null, Task.NEXT, List.of());
        sync(list, "google://" + EMAIL);
        host.taskPage.open(new TaskStore(parts).load(context).get(0));
        settle();

        serve("g1", TaskEdit.at("20261111T100000Z").withText("Call Ann", "Theirs"));
        sync(list, "google://" + EMAIL);
        type(R.id.task_page, R.string.task_field_notes, "Mine");
        host.taskPage.save();
        settle();

        assertEquals("nothing is written until it is settled", "Theirs",
                new TaskStore(parts).load(context).get(0).full().description());
        assertEquals("the task opens on the conflict page", Panels.EVENT_VIEW, host.screen);
        AlertDialog asked = tap(R.id.event_view_page, R.string.event_field_description);
        View offered = asked.getWindow().getDecorView();
        assertTrue("both notes are offered", shows(offered, "Mine") && shows(offered, "Theirs"));
        asked.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        shadowOf(Looper.getMainLooper()).idle();

        host.eventView.save();
        settle();
        assertEquals("the side kept is stored", "Mine",
                new TaskStore(parts).load(context).get(0).full().description());
        assertEquals("and the page goes back to the tasks", Panels.TASKS, host.screen);
    }

    @Test
    public void aLocationBothSidesChangedIsAskedNotOverwritten() throws Exception {
        String lunch = LUNCH.replace("SUMMARY:Lunch\r\n", "SUMMARY:Lunch\r\nLOCATION:Room 1\r\n");
        EventStore.StoredCalendar calendar = davEntry(lunch);
        host.eventView.open(null, events.loadEvents().get(0), calendar);
        settle();

        serveEntry(lunch.replace("Room 1", "Room 4"));
        sync(calendar, "https://dav.example.com/");
        type(R.id.event_view_page, R.string.event_field_location, "Room 5");
        host.eventView.save();
        settle();

        assertEquals("nothing is written until it is settled", "Room 4", storedEntry().location());
        assertEquals("the page stays on the entry", Panels.EVENT_VIEW, host.screen);
        AlertDialog asked = tap(R.id.event_view_page, R.string.event_field_location);
        View offered = asked.getWindow().getDecorView();
        assertTrue(
                "both locations are offered", shows(offered, "Room 4") && shows(offered, "Room 5"));
        asked.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        shadowOf(Looper.getMainLooper()).idle();

        host.eventView.save();
        settle();
        assertEquals("the side kept is stored", "Room 5", storedEntry().location());
    }

    @Test
    public void aCategoryAddedOnEachSideKeepsBoth() throws Exception {
        EventStore.StoredCalendar calendar = davTasks();
        CalendarParts parts = new CalendarParts(pimdir, clients, events);
        new TaskWrites(parts).add(calendar, "Call Ann", null, null, Task.NEXT, List.of("a"));
        sync(calendar, "https://dav.example.com/");
        host.taskPage.open(new TaskStore(parts).load(context).get(0));
        settle();

        serve("g1", TaskEdit.at("20261111T100000Z").withCategories("a,home"));
        sync(calendar, "https://dav.example.com/");
        tap(R.id.task_page, R.string.task_field_categories)
                .getButton(AlertDialog.BUTTON_NEUTRAL)
                .performClick();
        shadowOf(Looper.getMainLooper()).idle();
        confirm("work");
        host.taskPage.save();
        settle();

        List<String> categories =
                new TaskStore(parts).load(context).get(0).full().categories();
        assertTrue(
                "the category the pass added and the one typed both stay, not " + categories,
                categories.containsAll(List.of("a", "home", "work")));
    }

    @Test
    public void anAlarmAddedOnEachSideKeepsBoth() throws Exception {
        EventStore.StoredCalendar calendar = davEntry(LUNCH);
        host.eventView.open(null, events.loadEvents().get(0), calendar);
        settle();

        serveEntry(
                LUNCH.replace(
                        "END:VEVENT",
                        "BEGIN:VALARM\r\nACTION:DISPLAY\r\nDESCRIPTION:Lunch\r\n"
                                + "TRIGGER:-PT1H\r\nEND:VALARM\r\nEND:VEVENT"));
        sync(calendar, "https://dav.example.com/");
        View add = actionOf(host.findViewById(R.id.event_view_page), R.string.event_section_alarms);
        assertNotNull("the alarms card has an add button", add);
        add.performClick();
        AlertDialog form = ShadowAlertDialog.getLatestAlertDialog();
        form.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        shadowOf(Looper.getMainLooper()).idle();
        host.eventView.save();
        settle();

        assertEquals(
                "the alarm the pass added and the one added here both stay, "
                        + failure("event save failed"),
                2,
                CalendarClient.readAlarms(events.loadEvents().get(0).ical()).size());
    }

    @Test
    public void anOccurrenceThePassMovedKeepsItsNewTime() throws Exception {
        String series =
                "BEGIN:VCALENDAR\r\nVERSION:2.0\r\nPRODID:-//test//EN\r\nBEGIN:VEVENT\r\n"
                        + "UID:standup\r\nDTSTAMP:20261101T090000Z\r\n"
                        + "DTSTART:20261117T100000Z\r\nDTEND:20261117T110000Z\r\n"
                        + "RRULE:FREQ=WEEKLY;COUNT=4\r\nSUMMARY:Standup\r\n"
                        + "END:VEVENT\r\nEND:VCALENDAR\r\n";
        EventStore.StoredCalendar calendar = davEntry(series);
        EventStore.StoredEvent entry = events.loadEvents().get(0);
        Occurrence second =
                CalendarClient.expandEvent(entry.ical(), "20261123T000000", "20261126T000000")
                        .get(0);
        host.eventView.open(second, entry, calendar);
        settle();

        // NOTE: the server moves that one occurrence an hour on.
        serveEntry(
                series.replace(
                        "END:VEVENT\r\nEND:VCALENDAR",
                        "END:VEVENT\r\nBEGIN:VEVENT\r\nUID:standup\r\n"
                                + "DTSTAMP:20261111T100000Z\r\n"
                                + "RECURRENCE-ID:20261124T100000Z\r\n"
                                + "DTSTART:20261124T110000Z\r\nDTEND:20261124T120000Z\r\n"
                                + "SUMMARY:Standup\r\nEND:VEVENT\r\nEND:VCALENDAR"));
        sync(calendar, "https://dav.example.com/");
        type(R.id.event_view_page, R.string.event_field_summary, "Standup moved");
        host.eventView.save();
        shadowOf(ShadowAlertDialog.getLatestAlertDialog()).clickOnItem(0);
        settle();

        EventDetail stored =
                CalendarClient.readEvent(events.loadEvents().get(0).ical(), "20261124T100000Z");
        assertEquals(
                "the title typed lands on the occurrence, " + failure("event save failed"),
                "Standup moved",
                stored.summary());
        assertEquals("at the time the pass moved it to", "20261124T110000",
                stored.start().time());
    }

    @Test
    public void aMoveOverABodyThePassReplacedIsRefused() throws Exception {
        events.replaceCalendars(
                EMAIL,
                List.of(
                        new Calendar("list-1", "My Tasks", LIST, "", null, "", true, true),
                        new Calendar(
                                "list-2", "Errands", "google://" + EMAIL + "/tasks/list-2", "",
                                null, "", true, true)));
        EventStore.StoredCalendar from = calendarNamed("My Tasks");
        EventStore.StoredCalendar into = calendarNamed("Errands");
        CalendarParts parts = new CalendarParts(pimdir, clients, events);
        new TaskWrites(parts).add(from, "Call Ann", null, null, Task.NEXT, List.of());
        sync(from, "google://" + EMAIL);
        TaskStore.Stored read = new TaskStore(parts).load(context).get(0);

        serve("g1", TaskEdit.at("20261111T100000Z").withText("Call Ann", "From Google"));
        sync(from, "google://" + EMAIL);
        Exception refused = null;
        try {
            new TaskWrites(parts).moveTo(read, into, TaskEdit.at(TaskWrites.now()));
        } catch (Exception error) {
            refused = error;
        }

        assertNotNull("a move over the body the pass replaced is refused", refused);
        List<TaskStore.Stored> held = new TaskStore(parts).load(context);
        assertEquals("nothing moved", 1, held.size());
        assertEquals("the task stays where it was", from.id, held.get(0).calendar.id);
        assertEquals("with the notes the pass brought", "From Google",
                held.get(0).full().description());
    }

    @Test
    public void aClarifiedItemThePassChangedIsMerged() throws Exception {
        EventStore.StoredCalendar calendar = davTasks();
        CalendarParts parts = new CalendarParts(pimdir, clients, events);
        new TaskWrites(parts).capture(calendar, "Call Ann", null, null);
        sync(calendar, "https://dav.example.com/");
        host.clarify.open(new TaskStore(parts).load(context));
        settle();

        serve("g1", TaskEdit.at("20261111T100000Z").withText("Call Ann", "From the server"));
        sync(calendar, "https://dav.example.com/");
        View option = clickable(host.findViewById(R.id.clarify_page),
                host.getString(R.string.clarify_someday));
        assertNotNull("the step offers Someday", option);
        option.performClick();
        settle();

        Task stored = new TaskStore(parts).load(context).get(0).full();
        assertEquals("the item is clarified into Someday, " + failure("clarify failed"),
                Task.SOMEDAY, stored.list());
        assertEquals("beside the notes the pass brought", "From the server",
                stored.description());
    }

    @Test
    public void aDeleteOverAnEntryThePassChangedIsRefused() throws Exception {
        EventStore.StoredCalendar calendar = davEntry(LUNCH);
        host.eventView.open(null, events.loadEvents().get(0), calendar);
        settle();

        serveEntry(LUNCH.replace("SUMMARY:Lunch\r\n", "SUMMARY:Lunch\r\nLOCATION:Room 4\r\n"));
        sync(calendar, "https://dav.example.com/");
        host.eventView.confirmDelete();
        ShadowAlertDialog.getLatestAlertDialog()
                .getButton(AlertDialog.BUTTON_POSITIVE)
                .performClick();
        settle();

        assertEquals("the entry the pass changed is not deleted", 1, events.loadEvents().size());
        assertEquals("the page stays on it", Panels.EVENT_VIEW, host.screen);
        assertTrue("as the pass left it",
                shows(host.findViewById(R.id.event_view_page), "Room 4"));
    }

    @Test
    public void aTickOnARowThePassChangedIsAppliedToIt() throws Exception {
        EventStore.StoredCalendar list = taskList();
        CalendarParts parts = new CalendarParts(pimdir, clients, events);
        new TaskWrites(parts).add(list, "Call Ann", null, null, Task.NEXT, List.of());
        sync(list, "google://" + EMAIL);
        host.tasksList.reload();
        settle();

        serve("g1", TaskEdit.at("20261111T100000Z").withText("Call Ann", "From Google"));
        sync(list, "google://" + EMAIL);
        View tick = clickableById(host.findViewById(R.id.tasks_list), R.id.task_done);
        assertNotNull("the row has its tick", tick);
        tick.performClick();
        settle();

        Task stored = new TaskStore(parts).load(context).get(0).full();
        assertTrue("the tick closes the task, " + failure("task edit failed"), stored.closed());
        assertEquals("keeping the notes the pass brought", "From Google", stored.description());
    }

    @Test
    public void aBatchOverARowThePassChangedIsAppliedToIt() throws Exception {
        EventStore.StoredCalendar list = taskList();
        CalendarParts parts = new CalendarParts(pimdir, clients, events);
        new TaskWrites(parts).add(list, "Call Ann", null, null, Task.NEXT, List.of());
        sync(list, "google://" + EMAIL);
        List<TaskStore.Stored> selected = new TaskStore(parts).load(context);

        serve("g1", TaskEdit.at("20261111T100000Z").withText("Call Ann", "From Google"));
        sync(list, "google://" + EMAIL);
        Field field = TasksList.class.getDeclaredField("batch");
        field.setAccessible(true);
        Object batch = field.get(host.tasksList);
        java.lang.reflect.Method status =
                batch.getClass().getDeclaredMethod("status", List.class);
        status.setAccessible(true);
        status.invoke(batch, selected);
        // NOTE: Done, third of the states offered.
        shadowOf(ShadowAlertDialog.getLatestAlertDialog()).clickOnItem(2);
        settle();

        Task stored = new TaskStore(parts).load(context).get(0).full();
        assertTrue("the batch closes the task, " + failure("task batch write failed"),
                stored.closed());
        assertEquals("keeping the notes the pass brought", "From Google", stored.description());
    }

    @Test
    public void aClarifiedMoveAskedFirstIsMovedOnceSettled() throws Exception {
        events.replaceCalendars(
                EMAIL,
                List.of(
                        new Calendar("cal", "Home", DAV, "", null, "", true, true),
                        new Calendar(
                                "errands", "Errands", "https://dav.example.com/cal/errands/", "",
                                null, "", true, true)));
        EventStore.StoredCalendar home = calendarNamed("Home");
        CalendarParts parts = new CalendarParts(pimdir, clients, events);
        new TaskWrites(parts).capture(home, "Call Ann", null, null);
        sync(home, "https://dav.example.com/");
        host.clarify.open(new TaskStore(parts).load(context));
        settle();

        View page = host.findViewById(R.id.clarify_page);
        rowOf(page, "Call Ann").performClick();
        confirm("Call Ann today");
        serve("g1", TaskEdit.at("20261111T100000Z").withText("Call Ann first", null));
        sync(home, "https://dav.example.com/");
        for (int option :
                new int[] {
                    R.string.clarify_yes_something, R.string.clarify_longer, R.string.clarify_mine
                }) {
            clickable(page, host.getString(option)).performClick();
            shadowOf(Looper.getMainLooper()).idle();
        }
        rowOf(page, host.getString(R.string.task_section_calendar)).performClick();
        AlertDialog calendars = ShadowAlertDialog.getLatestAlertDialog();
        CharSequence[] offered = shadowOf(calendars).getItems();
        for (int index = 0; index < offered.length; index++) {
            if (offered[index].toString().contains("Errands")) {
                shadowOf(calendars).clickOnItem(index);
            }
        }
        shadowOf(Looper.getMainLooper()).idle();
        clickable(page, host.getString(R.string.clarify_save_next)).performClick();
        settle();

        assertEquals("the title both sides changed is asked", Panels.EVENT_VIEW, host.screen);
        tap(R.id.event_view_page, R.string.event_field_summary)
                .getButton(AlertDialog.BUTTON_POSITIVE)
                .performClick();
        shadowOf(Looper.getMainLooper()).idle();
        host.eventView.save();
        settle();

        TaskStore.Stored moved = new TaskStore(parts).load(context).get(0);
        assertEquals("the title kept lands", "Call Ann today", moved.full().summary());
        assertEquals(
                "and the move asked waits for it, then is done, " + failure("clarify move"),
                "Errands",
                moved.calendar.name);
    }

    @Test
    public void backFromAStaleTaskConflictAsksThenGoesToTheTasks() throws Exception {
        EventStore.StoredCalendar list = taskList();
        CalendarParts parts = new CalendarParts(pimdir, clients, events);
        new TaskWrites(parts).add(list, "Call Ann", "Base", null, Task.NEXT, List.of());
        sync(list, "google://" + EMAIL);
        host.taskPage.open(new TaskStore(parts).load(context).get(0));
        settle();
        serve("g1", TaskEdit.at("20261111T100000Z").withText("Call Ann", "Theirs"));
        sync(list, "google://" + EMAIL);
        type(R.id.task_page, R.string.task_field_notes, "Mine");
        host.taskPage.save();
        settle();
        assertEquals(Panels.EVENT_VIEW, host.screen);
        AlertDialog before = ShadowAlertDialog.getLatestAlertDialog();

        host.onBackPressed();
        shadowOf(Looper.getMainLooper()).idle();

        AlertDialog asked = ShadowAlertDialog.getLatestAlertDialog();
        assertTrue("back asks before dropping the edit",
                asked != before && asked.isShowing() && host.screen == Panels.EVENT_VIEW);
        asked.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        shadowOf(Looper.getMainLooper()).idle();
        assertEquals("then goes back to the tasks", Panels.TASKS, host.screen);
        assertEquals("writing nothing", "Theirs",
                new TaskStore(parts).load(context).get(0).full().description());
    }

    @Test
    public void aBatchDeleteOverATaskThePassChangedSaysSo() throws Exception {
        EventStore.StoredCalendar list = taskList();
        CalendarParts parts = new CalendarParts(pimdir, clients, events);
        new TaskWrites(parts).add(list, "Call Ann", null, null, Task.NEXT, List.of());
        sync(list, "google://" + EMAIL);
        List<TaskStore.Stored> selected = new TaskStore(parts).load(context);
        serve("g1", TaskEdit.at("20261111T100000Z").withText("Call Ann", "From Google"));
        sync(list, "google://" + EMAIL);

        batch("delete", selected);
        ShadowAlertDialog.getLatestAlertDialog()
                .getButton(AlertDialog.BUTTON_POSITIVE)
                .performClick();
        settle();

        assertEquals("the task the pass changed is not deleted", 1,
                new TaskStore(parts).load(context).size());
        assertEquals(
                "the batch says it changed meanwhile",
                host.getString(
                        host.getResources()
                                .getIdentifier("task_batch_changed", "string",
                                        host.getPackageName())),
                org.robolectric.shadows.ShadowToast.getTextOfLatestToast());
    }

    @Test
    public void aBatchDueOverADueThePassChangedIsNotOverwritten() throws Exception {
        EventStore.StoredCalendar list = taskList();
        CalendarParts parts = new CalendarParts(pimdir, clients, events);
        new TaskWrites(parts)
                .create(
                        list,
                        TaskEdit.at(TaskWrites.now())
                                .withText("Call Ann", null)
                                .withDue(new DateEdit("20261115", EventTimeKind.DATE, "")));
        sync(list, "google://" + EMAIL);
        List<TaskStore.Stored> selected = new TaskStore(parts).load(context);
        selected.get(0).full();
        serve("g1", TaskEdit.at("20261111T100000Z")
                .withDue(new DateEdit("20261201", EventTimeKind.DATE, "")));
        sync(list, "google://" + EMAIL);

        batch("due", selected);
        ShadowAlertDialog.getLatestAlertDialog()
                .getButton(AlertDialog.BUTTON_POSITIVE)
                .performClick();
        settle();

        assertEquals("the due date the pass set stays", "20261201",
                new TaskStore(parts).load(context).get(0).full().due().time().substring(0, 8));
        assertEquals(
                "and the batch says it left that task",
                host.getString(
                        host.getResources()
                                .getIdentifier("task_batch_changed", "string",
                                        host.getPackageName())),
                org.robolectric.shadows.ShadowToast.getTextOfLatestToast());
    }

    /** The stored calendar called {@code name}. */
    private EventStore.StoredCalendar calendarNamed(String name) {
        for (EventStore.StoredCalendar calendar : events.loadCalendars()) {
            if (calendar.name.equals(name)) {
                return calendar;
            }
        }
        throw new AssertionError("no calendar " + name);
    }

    /** Runs the Tasks list's batch {@code action} over {@code tasks}. */
    private void batch(String action, List<TaskStore.Stored> tasks) throws Exception {
        Field field = TasksList.class.getDeclaredField("batch");
        field.setAccessible(true);
        Object batch = field.get(host.tasksList);
        java.lang.reflect.Method method = batch.getClass().getDeclaredMethod(action, List.class);
        method.setAccessible(true);
        method.invoke(batch, tasks);
        shadowOf(Looper.getMainLooper()).idle();
    }

    /** {@code body} on the CalDAV server, synced into its stored calendar. */
    private EventStore.StoredCalendar davEntry(String body) {
        events.replaceCalendars(
                EMAIL, List.of(new Calendar("cal", "Home", DAV, "", null, "", true)));
        EventStore.StoredCalendar calendar = events.loadCalendars().get(0);
        server.items.put("lunch.ics", new String[] {"e1", body});
        sync(calendar, "https://dav.example.com/");
        return calendar;
    }

    /** The server's entry changed to {@code body}, at a new ETag. */
    private void serveEntry(String body) {
        server.items.put("lunch.ics", new String[] {"e2", body});
    }

    /** The server's task {@code id} changed by {@code edit}, at a new ETag. */
    private void serve(String id, TaskEdit edit) {
        String[] held = server.items.get(id);
        server.items.put(id, new String[] {"e2", CalendarClient.writeTask(held[1], edit)});
    }

    /** The one stored entry, read. */
    private EventDetail storedEntry() {
        return CalendarClient.readEvent(events.loadEvents().get(0).ical(), null);
    }

    /** Taps the row titled {@code label} on {@code page}: its dialog. */
    private AlertDialog tap(int page, int label) {
        View row = rowOf(host.findViewById(page), host.getString(label));
        assertNotNull("a row titled " + host.getString(label), row);
        row.performClick();
        return ShadowAlertDialog.getLatestAlertDialog();
    }

    /** Types {@code value} into the open text dialog, then confirms it. */
    private void confirm(String value) {
        AlertDialog dialog = ShadowAlertDialog.getLatestAlertDialog();
        first(dialog.getWindow().getDecorView()).setText(value);
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        shadowOf(Looper.getMainLooper()).idle();
    }

    /** The action of the card headed {@code title}, null when none. */
    private View actionOf(View view, int title) {
        if (view instanceof TextView
                && host.getString(title).contentEquals(((TextView) view).getText())) {
            ViewGroup header = (ViewGroup) view.getParent();
            for (int index = 0; index < header.getChildCount(); index++) {
                if (header.getChildAt(index).hasOnClickListeners()) {
                    return header.getChildAt(index);
                }
            }
            return null;
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int index = 0; index < group.getChildCount(); index++) {
                View found = actionOf(group.getChildAt(index), title);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    /** The nearest clickable holder of a text reading {@code text}. */
    private static View clickable(View view, String text) {
        if (view instanceof TextView && text.contentEquals(((TextView) view).getText())) {
            for (View at = view; at != null; at = (View) at.getParent()) {
                if (at.hasOnClickListeners()) {
                    return at;
                }
                if (!(at.getParent() instanceof View)) {
                    return null;
                }
            }
            return null;
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int index = 0; index < group.getChildCount(); index++) {
                View found = clickable(group.getChildAt(index), text);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    /** The view {@code id} of the first row a list's adapter draws. */
    private static View clickableById(android.widget.ListView list, int id) {
        android.widget.ListAdapter adapter = list.getAdapter();
        for (int position = 0; position < adapter.getCount(); position++) {
            View found = adapter.getView(position, null, list).findViewById(id);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    /** One CalDAV calendar taking tasks, stored. */
    private EventStore.StoredCalendar davTasks() {
        events.replaceCalendars(
                EMAIL, List.of(new Calendar("cal", "Home", DAV, "", null, "", true, true)));
        return events.loadCalendars().get(0);
    }

    /** The one Google task list, stored. */
    private EventStore.StoredCalendar taskList() {
        events.replaceCalendars(
                EMAIL, List.of(new Calendar("list-1", "My Tasks", LIST, "", null, "", true, true)));
        return events.loadCalendars().get(0);
    }

    /** One calendar pass of {@code calendar}, against the fake server. */
    private void sync(EventStore.StoredCalendar calendar, String base) {
        new CalendarEngine(
                        pimdir,
                        clients,
                        new RemoteAccount(
                                null,
                                new Account(base, EMAIL, "token"),
                                new PimdirAccount(context).idOf(EMAIL)))
                .sync(calendar.id);
    }

    /**
     * Types {@code value} into the dialog the row titled {@code label} opens
     * on {@code page}, then confirms it.
     */
    private void type(int page, int label, String value) {
        View row = rowOf(host.findViewById(page), host.getString(label));
        assertNotNull("a row titled " + host.getString(label), row);
        row.performClick();
        AlertDialog dialog = ShadowAlertDialog.getLatestAlertDialog();
        EditText input = first(dialog.getWindow().getDecorView());
        assertNotNull("the row's dialog takes a text", input);
        input.setText(value);
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        shadowOf(Looper.getMainLooper()).idle();
    }

    /** The clickable row whose title reads {@code title}, null when none. */
    private static View rowOf(View view, String title) {
        if (view instanceof TextView && title.contentEquals(((TextView) view).getText())) {
            View row = (View) view.getParent().getParent();
            return row.hasOnClickListeners() ? row : null;
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int index = 0; index < group.getChildCount(); index++) {
                View found = rowOf(group.getChildAt(index), title);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    /** The first text input under {@code view}, null when none. */
    private static EditText first(View view) {
        if (view instanceof EditText) {
            return (EditText) view;
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int index = 0; index < group.getChildCount(); index++) {
                EditText found = first(group.getChildAt(index));
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    /**
     * Whether a text under {@code view} reads {@code text}: a page renders
     * its rows anew, so only what it shows now is under it.
     */
    private static boolean shows(View view, String text) {
        if (view instanceof TextView && text.contentEquals(((TextView) view).getText())) {
            return true;
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int index = 0; index < group.getChildCount(); index++) {
                if (shows(group.getChildAt(index), text)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Lets the pages' reads and writes and what they post on the main thread
     * run out, a few rounds as each may start another.
     */
    private void settle() throws Exception {
        Field field = TasksList.class.getDeclaredField("reads");
        field.setAccessible(true);
        ExecutorService reads = (ExecutorService) field.get(host.tasksList);
        for (int round = 0; round < 4; round++) {
            reads.submit(() -> {}).get(10, TimeUnit.SECONDS);
            host.io.submit(() -> {}).get(10, TimeUnit.SECONDS);
            shadowOf(Looper.getMainLooper()).idle();
        }
    }

    /** The error a save logged under {@code prefix}, none for no failure. */
    private static String failure(String prefix) {
        for (ShadowLog.LogItem item : ShadowLog.getLogsForTag("pimalaya")) {
            if (item.msg.startsWith(prefix) && item.throwable != null) {
                return item.throwable.getMessage();
            }
        }
        return "no failure";
    }
}
