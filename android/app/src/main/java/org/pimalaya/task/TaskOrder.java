package org.pimalaya.task;

import org.pimalaya.calendar.Zones;

import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

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
