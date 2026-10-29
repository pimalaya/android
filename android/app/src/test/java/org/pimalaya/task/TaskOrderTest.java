package org.pimalaya.task;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.pimalaya.client.EventTime;
import org.pimalaya.client.EventTimeKind;
import org.pimalaya.client.Task;
import org.robolectric.RobolectricTestRunner;

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
}
