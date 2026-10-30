package org.pimalaya.task;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.pimalaya.client.EventTime;
import org.pimalaya.client.EventTimeKind;
import org.pimalaya.client.Task;
import org.pimalaya.client.TaskEdit;
import org.robolectric.RobolectricTestRunner;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * What a batch action writes per task (cairn/changes/task-batch-actions): the
 * Category sheet's merge, the state it leaves, the copies' order and content.
 */
@RunWith(RobolectricTestRunner.class)
public class TaskBatchTest {
    private static Task task(String uid, String parent, String state, String... categories) {
        return new Task(
                uid, uid, "", "", state == null ? Task.NEXT : state.toUpperCase(),
                state == null ? List.of() : List.of(state), List.of(categories), parent, 0,
                null, null, null, "", "", null, "");
    }

    private static TaskStore.Stored stored(Task task) {
        return new TaskStore.Stored(null, null, task);
    }

    @Test
    public void categoryAddsTheTickedTakesTheBlankAndKeepsThePartly() {
        Map<String, String> ticked = new LinkedHashMap<>();
        ticked.put("garden", "Garden");
        ticked.put("@phone", "@phone");

        // NOTE: @home is partly ticked on the sheet, so in neither set: the
        // task keeps it; Work is made blank, compared folded.
        List<String> merged =
                TaskBatch.merged(List.of("garden", "@home", "Work"), ticked, Set.of("work"));

        assertEquals(List.of("garden", "@home", "@phone"), merged);
    }

    @Test
    public void categoryMovesTheStateOnlyWhereAListChanged() {
        Task waiting = task("w", null, "waiting");
        Task next = task("n", null, null);

        assertEquals(Task.SOMEDAY, TaskBatch.stateAfter(waiting, Task.SOMEDAY, Set.of()));
        assertNull(TaskBatch.stateAfter(waiting, Task.WAITING, Set.of()));
        assertEquals(Task.NEXT, TaskBatch.stateAfter(waiting, null, Set.of(Task.WAITING)));
        assertNull(TaskBatch.stateAfter(next, null, Set.of(Task.WAITING)));
        assertNull(TaskBatch.stateAfter(waiting, null, Set.of()));
    }

    @Test
    public void copiesWriteEachParentBeforeItsChildren() {
        TaskStore.Stored child = stored(task("child", "parent", null));
        TaskStore.Stored grandchild = stored(task("grandchild", "child", null));
        TaskStore.Stored parent = stored(task("parent", null, null));
        TaskStore.Stored orphan = stored(task("orphan", "elsewhere", null));

        List<String> order = new ArrayList<>();
        for (TaskStore.Stored each :
                TaskBatch.parentsFirst(List.of(grandchild, child, orphan, parent))) {
            order.add(each.task.uid());
        }

        assertEquals(List.of("orphan", "parent", "child", "grandchild"), order);
    }

    @Test
    public void aCopyKeepsWhatTheTaskStatesUnderItsCopiedParent() {
        Task done =
                new Task(
                        "t", "Order the timber", "Two planks", "COMPLETED", Task.NEXT, List.of(),
                        List.of("Garden"), "p", 5, null,
                        new EventTime("20261105", EventTimeKind.DATE, "", null), null, "Sam",
                        "sam@example.org", "<id@example.org>", "");

        TaskEdit alone = TaskBatch.copyOf(done, null);
        TaskEdit under = TaskBatch.copyOf(done, "p-copy");

        assertEquals("Order the timber", alone.summary());
        assertEquals("Two planks", alone.description());
        assertEquals("Garden", alone.categories());
        assertEquals("5", alone.priority());
        assertEquals("20261105", alone.due().time());
        assertEquals("Sam", alone.contactName());
        assertEquals("<id@example.org>", alone.source());
        assertEquals(TaskOrder.DONE, alone.status());
        assertNull(alone.parent());
        assertEquals("p-copy", under.parent());
    }
}
