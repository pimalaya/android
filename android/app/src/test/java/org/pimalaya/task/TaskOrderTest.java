package org.pimalaya.task;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.pimalaya.client.EventTime;
import org.pimalaya.client.EventTimeKind;
import org.pimalaya.client.Task;
import org.robolectric.RobolectricTestRunner;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * How the lists order their tasks and how the category filter compares
 * (pimdir GUIDE §17, cairn/changes/gtd-hardening).
 */
@RunWith(RobolectricTestRunner.class)
public class TaskOrderTest {
    private static TaskStore.Stored task(
            String uid, String due, int priority, String created, String... categories) {
        EventTime when = due == null ? null : new EventTime(due, EventTimeKind.UTC, "", null);
        return new TaskStore.Stored(
                null,
                null,
                new Task(
                        uid, uid, "", "", Task.NEXT, List.of(), List.of(categories), null,
                        priority, null, when, null, "", "", null, created));
    }

    private static Task status(String status, String state, String completed) {
        EventTime done =
                completed == null ? null : new EventTime(completed, EventTimeKind.UTC, "", null);
        return new Task(
                "t", "t", "", status, state == null ? Task.NEXT : state.toUpperCase(java.util.Locale.ROOT),
                state == null ? List.of() : List.of(state), List.of(), null, 0, null, null, done,
                "", "", null, "");
    }

    private static List<String> uids(List<TaskStore.Stored> tasks) {
        List<String> uids = new ArrayList<>();
        for (TaskStore.Stored stored : tasks) {
            uids.add(stored.task.uid());
        }
        return uids;
    }

    @Test
    public void nextIsDueThenPriorityThenOldestCaptured() {
        List<TaskStore.Stored> next =
                new ArrayList<>(
                        List.of(
                                task("undated-new", null, 0, "20261020T090000Z"),
                                task("undated-old", null, 0, "20261001T090000Z"),
                                task("undated-urgent", null, 1, "20261025T090000Z"),
                                task("due-late", "20261110T090000", 1, "20261001T090000Z"),
                                task("due-none", "20261105T090000", 0, "20261001T090000Z"),
                                task("due-new", "20261105T090000", 2, "20261020T090000Z"),
                                task("due-old", "20261105T090000", 2, "20261002T090000Z")));

        next.sort(TaskOrder.NEXT);

        // NOTE: a due date first, soonest first; then PRIORITY 1 to 9, none
        // after 9; then the oldest captured, where the order stopped before.
        assertEquals(
                List.of(
                        "due-old",
                        "due-new",
                        "due-none",
                        "due-late",
                        "undated-urgent",
                        "undated-old",
                        "undated-new"),
                uids(next));
    }

    @Test
    public void theOtherListsAreOldestCapturedFirst() {
        List<TaskStore.Stored> inbox =
                new ArrayList<>(
                        List.of(
                                task("stamped-later", null, 1, "20261020T090000Z"),
                                task("created-first", null, 9, "20261001T090000Z")));

        inbox.sort(TaskOrder.CAPTURED);

        assertEquals(List.of("created-first", "stamped-later"), uids(inbox));
    }

    @Test
    public void aCategoryComparesFolded() {
        List<TaskStore.Stored> tasks =
                List.of(
                        task("a", null, 0, "", "Work"),
                        task("b", null, 0, "", " work "),
                        task("c", null, 0, "", "ÉTÉ"),
                        task("d", null, 0, "", "home"));

        assertTrue(TaskOrder.carries(tasks.get(1), "Work"));
        assertTrue(TaskOrder.carries(tasks.get(2), "été"));
        assertEquals(List.of("Work", "ÉTÉ", "home"), new ArrayList<>(TaskOrder.categories(tasks)));
    }

    @Test
    public void categoriesOnKeepATaskCarryingAnyOfThem() {
        TaskStore.Stored home = task("home", null, 0, "", "@Home");
        TaskStore.Stored phone = task("phone", null, 0, "", "Garden", "@phone");
        TaskStore.Stored garden = task("garden", null, 0, "", "Garden");
        java.util.Set<String> on = java.util.Set.of("@home", "@phone");

        assertTrue(TaskOrder.carriesAny(home, on));
        assertTrue(TaskOrder.carriesAny(phone, on));
        assertFalse(TaskOrder.carriesAny(garden, on));
        // NOTE: nothing on is every task.
        assertTrue(TaskOrder.carriesAny(garden, java.util.Set.of()));
    }

    @Test
    public void aStatusAndAListCombine() {
        Task waitingStarted = status("IN-PROCESS", "waiting", null);
        Task waitingDone = status("COMPLETED", "waiting", "20261030T090000");
        Task dropped = status("CANCELLED", null, null);
        Task plain = status("", null, null);

        // NOTE: with no status, the open tasks of the list, Next carrying no
        // state; a closed task is in none.
        assertTrue(TaskOrder.lists(null, Task.WAITING, waitingStarted));
        assertFalse(TaskOrder.lists(null, Task.NEXT, waitingStarted));
        assertTrue(TaskOrder.lists(null, Task.NEXT, plain));
        assertFalse(TaskOrder.lists(null, Task.WAITING, waitingDone));
        assertFalse(TaskOrder.lists(null, Task.NEXT, dropped));
        // NOTE: under a status, no list is any state, a list narrows it.
        assertTrue(TaskOrder.lists(TaskOrder.PROGRESS, Task.NEXT, waitingStarted));
        assertTrue(TaskOrder.lists(TaskOrder.PROGRESS, Task.WAITING, waitingStarted));
        assertFalse(TaskOrder.lists(TaskOrder.PROGRESS, Task.SOMEDAY, waitingStarted));
        assertFalse(TaskOrder.lists(TaskOrder.PROGRESS, Task.NEXT, plain));
        assertTrue(TaskOrder.lists(TaskOrder.DONE, Task.NEXT, waitingDone));
        assertTrue(TaskOrder.lists(TaskOrder.DONE, Task.WAITING, waitingDone));
        assertFalse(TaskOrder.lists(TaskOrder.DONE, Task.INBOX, waitingDone));
        assertFalse(TaskOrder.lists(TaskOrder.CANCELLED, Task.NEXT, waitingDone));
        assertTrue(TaskOrder.lists(TaskOrder.CANCELLED, Task.NEXT, dropped));
    }

    @Test
    public void doneIsTheLastCompletedFirst() {
        List<TaskStore.Stored> done =
                new ArrayList<>(
                        List.of(
                                new TaskStore.Stored(null, null, status("COMPLETED", null, null)),
                                new TaskStore.Stored(
                                        null, null, status("COMPLETED", null, "20261029T090000")),
                                new TaskStore.Stored(
                                        null, null, status("COMPLETED", null, "20261030T090000"))));

        done.sort(TaskOrder.COMPLETED);

        assertEquals("20261030T090000", done.get(0).task.completed().time());
        assertEquals("20261029T090000", done.get(1).task.completed().time());
        assertEquals(null, done.get(2).task.completed());
    }

    @Test
    public void theStatesAreReservedFolded() {
        assertTrue(TaskOrder.reserved(" Waiting "));
        assertTrue(TaskOrder.reserved("INBOX"));
        assertFalse(TaskOrder.reserved("someday-maybe"));
    }

    @Test
    public void dueComparesTheDayAndAnUndatedTaskPassesAnyAlone() {
        Task due =
                new Task(
                        "d", "d", "", "", Task.NEXT, List.of(), List.of(), null, 0, null,
                        new EventTime("20261105", EventTimeKind.DATE, "", null), null, "", "",
                        null, "");
        Task undated = task("u", null, 0, "").task;
        LocalDate day = LocalDate.of(2026, 11, 5);

        assertTrue(TaskOrder.dueMatches(due, TaskOrder.DUE_ON, day));
        assertFalse(TaskOrder.dueMatches(due, TaskOrder.DUE_BEFORE, day));
        assertTrue(TaskOrder.dueMatches(due, TaskOrder.DUE_BEFORE, day.plusDays(1)));
        assertFalse(TaskOrder.dueMatches(due, TaskOrder.DUE_AFTER, day));
        assertTrue(TaskOrder.dueMatches(due, TaskOrder.DUE_AFTER, day.minusDays(1)));
        assertTrue(TaskOrder.dueMatches(undated, TaskOrder.DUE_ANY, day));
        assertFalse(TaskOrder.dueMatches(undated, TaskOrder.DUE_BEFORE, day));
        assertFalse(TaskOrder.dueMatches(undated, TaskOrder.DUE_ON, day));
        assertFalse(TaskOrder.dueMatches(undated, TaskOrder.DUE_AFTER, day));
    }
}
