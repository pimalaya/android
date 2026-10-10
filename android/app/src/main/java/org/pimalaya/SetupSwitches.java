package org.pimalaya;

import java.util.EnumSet;
import java.util.Set;

/**
 * The setups' opt-in switches: the books in the phone's contacts, the
 * calendars in its calendar, and new-mail notifications.
 *
 * <p>Each starts off and turns on only once its permission prompt, asked as
 * it is switched on, came back granted: a refusal leaves it off.
 */
final class SetupSwitches {
    /** The phone apps the account's collections show in. */
    final Set<PhoneMirror> mirrors = EnumSet.noneOf(PhoneMirror.class);

    /** Whether the account's new mail notifies. */
    boolean notifies;

    /** Turns every switch off, for a new run of the setup. */
    void reset() {
        mirrors.clear();
        notifies = false;
    }

    /**
     * A mirror's switch moved: on only when its prompt granted it. Answers
     * whether it is on.
     */
    boolean mirror(PhoneMirror mirror, boolean on, Set<PhoneMirror> granted) {
        if (on && granted.contains(mirror)) {
            mirrors.add(mirror);
            return true;
        }
        mirrors.remove(mirror);
        return false;
    }

    /** The notifications' switch moved: on only when granted. Answers whether it is on. */
    boolean notify(boolean on, boolean granted) {
        notifies = on && granted;
        return notifies;
    }
}
