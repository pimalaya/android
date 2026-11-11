package org.pimalaya;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.content.Context;
import android.os.Looper;
import android.view.View;
import android.view.ViewParent;
import android.widget.ListAdapter;
import android.widget.ListView;

import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.pimalaya.account.PimDomain;
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
import org.pimalaya.client.Task;
import org.pimalaya.client.Transport;
import org.pimalaya.pimdir.PimdirAccount;
import org.pimalaya.pimdir.PimdirDb;
import org.pimalaya.task.TaskStore;
import org.pimalaya.task.TaskWrites;
import org.pimalaya.task.TasksList;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.shadows.ShadowLog;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

/** The Tasks tab around a calendar pass: its pull, and its rows after one. */
@RunWith(RobolectricTestRunner.class)
public class TasksTabSyncTest {
    private static final String EMAIL = "jane@gmail.com";
    private static final String LIST = "google://" + EMAIL + "/tasks/list-1";

    /** The app's screen, its syncs counted rather than run. */
    public static class TasksHost extends MainActivity {
        int calendarPulls;
        int otherPulls;

        @Override
        void syncCalendars() {
            calendarPulls++;
        }

        @Override
        void syncContacts() {
            otherPulls++;
        }

        @Override
        void syncAll() {
            otherPulls++;
        }
    }

    /** One Google task list, filing each create under an id of its own. */
    private static final class Google extends CalendarClient {
        /** The tasks by id, as (ETag, Google's copy). */
        final Map<String, String[]> tasks = new LinkedHashMap<>();

        @Override
        public EventDelta syncEvents(
                Transport transport, Account account, String calendarUrl, String cursor) {
            List<EventRef> changed = new ArrayList<>();
            List<Event> bodies = new ArrayList<>();
            for (Map.Entry<String, String[]> task : tasks.entrySet()) {
                changed.add(new EventRef(task.getKey(), task.getValue()[0]));
                bodies.add(new Event(task.getKey(), task.getValue()[0], task.getValue()[1]));
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
            String id = "g" + (tasks.size() + 1);
            tasks.put(id, new String[] {"e1", ical});
            return new EventRef(id, "e1");
        }
    }

    private Context context;

    @Before
    public void setUp() {
        FakeAndroidKeyStore.install();
        context = RuntimeEnvironment.getApplication();
    }

    @Test
    public void pullingTheTasksListSyncsTheCalendars() throws Exception {
        TasksHost host = Robolectric.buildActivity(TasksHost.class).create().get();

        ViewParent parent = host.findViewById(R.id.tasks_list).getParent();
        assertTrue(
                "the Tasks list sits in a pull-down, not " + parent.getClass().getName(),
                parent instanceof SwipeRefreshLayout);
        SwipeRefreshLayout refresh = (SwipeRefreshLayout) parent;
        // NOTE: the listener a finished drag calls; Robolectric runs no
        // spinner animation to reach it.
        Field listener = SwipeRefreshLayout.class.getDeclaredField("mListener");
        listener.setAccessible(true);
        ((SwipeRefreshLayout.OnRefreshListener) listener.get(refresh)).onRefresh();

        assertEquals("the pull syncs the calendars", 1, host.calendarPulls);
        assertEquals("and nothing else", 0, host.otherPulls);
        assertFalse("its spinner leaves the wait to the dock", refresh.isRefreshing());
    }

    @Test
    public void aTaskTickedAfterThePassFilingItIsDone() throws Exception {
        TasksHost host = Robolectric.buildActivity(TasksHost.class).create().get();
        PimdirDb pimdir = App.of(context).pimdir();
        Google google = new Google();
        Clients clients =
                new Clients(
                        new AccountClient(), new ContactClient(), google, new MailClient(),
                        new OfflineClient());
        EventStore events = new EventStore(context, pimdir);
        events.replaceCalendars(
                EMAIL, List.of(new Calendar("list-1", "My Tasks", LIST, "", null, "", true, true)));
        EventStore.StoredCalendar list = events.loadCalendars().get(0);
        CalendarParts parts = new CalendarParts(pimdir, clients, events);
        new TaskWrites(parts).add(list, "Call Ann", null, null, Task.NEXT, List.of());
        host.tasksList.reload();
        settle(host);

        // NOTE: the drawer's sync, the create filed under Google's id, then
        // the activity told its calendar pass ended.
        new CalendarEngine(
                        pimdir,
                        clients,
                        new RemoteAccount(
                                null,
                                new Account("google://" + EMAIL, EMAIL, "token"),
                                new PimdirAccount(context).idOf(EMAIL)))
                .sync(list.id);
        assertEquals(List.of("g1"), new ArrayList<>(google.tasks.keySet()));
        host.reload(PimDomain.CALENDAR);
        settle(host);

        tick(host);
        settle(host);

        List<TaskStore.Stored> tasks = new TaskStore(parts).load(context);
        assertEquals(1, tasks.size());
        assertTrue(
                "the tick closes the task, not " + editFailure(),
                tasks.get(0).task.closed());
    }

    /** Taps the tick of the one task row the Tasks list shows. */
    private static void tick(TasksHost host) {
        ListView list = host.findViewById(R.id.tasks_list);
        ListAdapter adapter = list.getAdapter();
        List<View> ticks = new ArrayList<>();
        for (int position = 0; position < adapter.getCount(); position++) {
            View tick = adapter.getView(position, null, list).findViewById(R.id.task_done);
            if (tick != null) {
                ticks.add(tick);
            }
        }
        assertEquals("one task row", 1, ticks.size());
        ticks.get(0).performClick();
    }

    /**
     * Lets the tab's reads, the app's writes and what they post on the main
     * thread run out, a few rounds as each may start another.
     */
    private static void settle(TasksHost host) throws Exception {
        Field field = TasksList.class.getDeclaredField("reads");
        field.setAccessible(true);
        ExecutorService reads = (ExecutorService) field.get(host.tasksList);
        for (int round = 0; round < 4; round++) {
            reads.submit(() -> {}).get(10, TimeUnit.SECONDS);
            host.io.submit(() -> {}).get(10, TimeUnit.SECONDS);
            shadowOf(Looper.getMainLooper()).idle();
        }
    }

    /** The error a task edit logged, none when it went through. */
    private static String editFailure() {
        for (ShadowLog.LogItem item : ShadowLog.getLogsForTag("pimalaya")) {
            if (item.msg.startsWith("task edit failed") && item.throwable != null) {
                return item.throwable.getMessage();
            }
        }
        return "no failure";
    }
}
