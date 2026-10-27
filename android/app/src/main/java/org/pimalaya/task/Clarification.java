package org.pimalaya.task;

import java.util.ArrayList;
import java.util.List;

/**
 * What clarifying one Inbox item decides, step by step (GTD's order).
 *
 * <p>One record the person fills by hand today, and a model may prefill
 * later (cairn/changes/gtd, Later): the flow asks each question with the
 * record's answer already chosen when it has one, so it never assumes it
 * starts empty. A null answer is one not given yet.
 */
final class Clarification {
    /** Not actionable: what to keep it as. */
    enum Kept {
        SOMEDAY,
        REFERENCE,
        DROPPED,
    }

    Boolean actionable;
    Kept kept;
    Boolean twoMinutes;
    Boolean delegated;
    String delegateTo;
    Boolean project;

    /** The next action's text, the item's own title until changed. */
    String nextAction;

    final List<String> contexts = new ArrayList<>();

    /** The project the next action belongs to, a UID; empty for none. */
    String parent = "";

    /** The next action's due day, civil {@code YYYYMMDD}; empty for none. */
    String due = "";
}
