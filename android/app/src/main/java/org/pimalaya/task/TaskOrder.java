package org.pimalaya.task;

import org.pimalaya.calendar.Zones;
import org.pimalaya.client.Task;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * How the lists order their tasks and how the category filter compares, as
 * pimdir GUIDE §17 states both (cairn/changes/gtd-hardening).
 *
 * <p>Next: the soonest {@code DUE} first, a task stating none after every one
 * that does, then by {@code PRIORITY}, 1 first and 9 last, 0 after 9, then the
 * oldest captured first. Inbox, Waiting and Someday: the oldest captured first.
 * Captured is {@code CREATED}, else {@code DTSTAMP} ({@link
 * TaskStore#capturedAt}). A category compares folded, as the store's {@code
 * category:} does: Unicode lowercase, locale-independent, trimmed.
 */
final class TaskOrder {
    private TaskOrder() {}

    /** Next's order. */
    static final Comparator<TaskStore.Stored> NEXT =
            ((Comparator<TaskStore.Stored>) TaskOrder::byDue)
                    .thenComparingInt((TaskStore.Stored stored) -> rank(stored.task.priority()))
                    .thenComparingLong(stored -> TaskStore.capturedAt(stored.task));

    /** Inbox's, Waiting's and Someday's order. */
    static final Comparator<TaskStore.Stored> CAPTURED =
            Comparator.comparingLong(stored -> TaskStore.capturedAt(stored.task));

    /** Cancelled's order: the last captured first. */
    static final Comparator<TaskStore.Stored> RECENT = CAPTURED.reversed();

    /** Done's order: the last {@code COMPLETED} first, one stating none after. */
    static final Comparator<TaskStore.Stored> COMPLETED =
            Comparator.comparingLong(
                    (TaskStore.Stored stored) ->
                            stored.task.completed() == null
                                    ? Long.MAX_VALUE
                                    : -Zones.instant(stored.task.completed()));

    /** The view of the tasks under way, a {@code STATUS} (RFC 5545 3.8.1.11). */
    static final String PROGRESS = "IN-PROCESS";

    /** The view of the done tasks. */
    static final String DONE = "COMPLETED";

    /** The view of the dropped tasks. */
    static final String CANCELLED = "CANCELLED";

    /** The state of an open task not under way, with or without a STATUS. */
    static final String TODO = "NEEDS-ACTION";

    /**
     * A task's state, as the list's Status filter tells them: {@link #DONE},
     * {@link #CANCELLED}, {@link #PROGRESS}, else {@link #TODO}, a task
     * stating no {@code STATUS} included.
     */
    static String stateOf(Task task) {
        if (DONE.equals(task.status())) {
            return DONE;
        }
        if (CANCELLED.equals(task.status())) {
            return CANCELLED;
        }
        return underWay(task) ? PROGRESS : TODO;
    }

    /** Whether an open task is under way: {@code STATUS:IN-PROCESS}. */
    static boolean underWay(Task task) {
        return !task.closed() && PROGRESS.equals(task.status());
    }

    /**
     * Whether the two filter rows list {@code task}: {@code status} one of
     * {@link #PROGRESS}, {@link #DONE} and {@link #CANCELLED}, null for the
     * open tasks; {@code list} a GTD list. With no status, the open tasks of
     * that list, {@link Task#NEXT} the ones carrying no state; under a status,
     * its tasks in that list, {@link Task#NEXT} then meaning any state, since
     * a closed or started task keeps the state it had.
     */
    static boolean lists(String status, String list, Task task) {
        if (status == null) {
            return !task.closed() && task.in(list);
        }
        boolean inList = Task.NEXT.equals(list) || task.in(list);
        if (PROGRESS.equals(status)) {
            return underWay(task) && inList;
        }
        return status.equals(task.status()) && inList;
    }

    /** Due ones first, soonest first. */
    private static int byDue(TaskStore.Stored left, TaskStore.Stored right) {
        boolean leftDue = left.task.due() != null;
        boolean rightDue = right.task.due() != null;
        if (leftDue != rightDue) {
            return leftDue ? -1 : 1;
        }
        return leftDue
                ? Long.compare(Zones.instant(left.task.due()), Zones.instant(right.task.due()))
                : 0;
    }

    /** A priority's rank, undefined last (RFC 5545 3.8.1.9). */
    private static int rank(int priority) {
        return priority <= 0 || priority > 9 ? 10 : priority;
    }

    /** A category as the filter compares it. */
    static String fold(String category) {
        return category.toLowerCase(Locale.ROOT).trim();
    }

    /**
     * Whether {@code category} is one of the three reserved values, the
     * state rather than a person's category (pimdir GUIDE §17).
     */
    static boolean reserved(String category) {
        String folded = fold(category);
        return folded.equals("inbox") || folded.equals("waiting") || folded.equals("someday");
    }

    /** Whether a task carries {@code category}, compared folded. */
    static boolean carries(TaskStore.Stored stored, String category) {
        String wanted = fold(category);
        for (String held : stored.task.categories()) {
            if (fold(held).equals(wanted)) {
                return true;
            }
        }
        return false;
    }

    /** The due filter off: every task, dated or not. */
    static final String DUE_ANY = "any";

    /** Due strictly before the day. */
    static final String DUE_BEFORE = "before";

    /** Due on the day. */
    static final String DUE_ON = "on";

    /** Due strictly after the day. */
    static final String DUE_AFTER = "after";

    /**
     * Whether a task passes the due filter: its {@code DUE}'s day on the
     * reader's calendar against {@code day} as {@code mode} says; a task
     * stating no {@code DUE} passes {@link #DUE_ANY} alone.
     */
    static boolean dueMatches(Task task, String mode, LocalDate day) {
        if (DUE_ANY.equals(mode)) {
            return true;
        }
        if (task.due() == null) {
            return false;
        }
        LocalDate due =
                Instant.ofEpochMilli(Zones.instant(task.due()))
                        .atZone(ZoneId.systemDefault())
                        .toLocalDate();
        switch (mode) {
            case DUE_BEFORE:
                return due.isBefore(day);
            case DUE_ON:
                return due.isEqual(day);
            case DUE_AFTER:
                return due.isAfter(day);
            default:
                return true;
        }
    }

    /**
     * Whether a task passes the category filter: it carries any of {@code
     * folded}, categories already folded; every task when none is on.
     */
    static boolean carriesAny(TaskStore.Stored stored, Set<String> folded) {
        if (folded.isEmpty()) {
            return true;
        }
        for (String held : stored.task.categories()) {
            if (folded.contains(fold(held))) {
                return true;
            }
        }
        return false;
    }

    /**
     * Every category the tasks name, one per folded spelling, the first seen
     * kept, in first-seen order.
     */
    static Collection<String> categories(List<TaskStore.Stored> tasks) {
        Map<String, String> seen = new LinkedHashMap<>();
        for (TaskStore.Stored stored : tasks) {
            for (String category : stored.task.categories()) {
                seen.putIfAbsent(fold(category), category);
            }
        }
        return seen.values();
    }
}
