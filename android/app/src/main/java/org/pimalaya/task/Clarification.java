package org.pimalaya.task;

import org.pimalaya.calendar.EventStore;

import java.util.ArrayList;
import java.util.List;

/**
 * What clarifying one Inbox item decides, step by step (GTD's order).
 *
 * <p>One record the person fills by hand today, and a model may prefill
 * later (cairn/changes/gtd, Later): the flow asks each question with the
 * record's answer already chosen when it has one, so it never assumes it
 * starts empty. A null answer is one not given yet. Its outcome is an edit
 * of the same item (cairn/changes/gtd-by-categories).
 */
final class Clarification {
    /** Not actionable: what to keep it as. */
    enum Kept {
        SOMEDAY,
        DELETED,
    }

    Boolean actionable;
    Kept kept;
    Boolean twoMinutes;
    Boolean delegated;
    String delegateTo;

    /** The item's title, rephrased or not: the next action's text. */
    String title;

    /** The person's categories: contexts, a project, anything. */
    final List<String> categories = new ArrayList<>();

    /** The task it sits under, a UID; empty for none. */
    String parent = "";

    /** The next action's due day, civil {@code YYYYMMDD}; empty for none. */
    String due = "";

    /** The calendar it is filed in, its own until another is picked. */
    EventStore.StoredCalendar target;
}
