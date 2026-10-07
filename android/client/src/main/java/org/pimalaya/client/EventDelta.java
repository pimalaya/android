package org.pimalaya.client;

import java.util.List;

/**
 * One calendar enumerated from a cursor: which events moved, which went,
 * and where the next round resumes.
 *
 * <p>The calendar twin of {@link CardDelta}, and no bodies, for the same
 * reason: a pass asks which members changed and reads the ones it has to.
 * Except where the round had to read them anyway: Google's and JMAP's
 * complete rounds list every event whole and hand the bodies over in
 * {@link #bodies}, rather than have each one read a second time.
 */
public final class EventDelta {
    /** Resource names created or updated since the cursor, with ETags. */
    public final List<EventRef> changed;

    /**
     * The bodies the round already held, under the same names and at the
     * same revisions as {@link #changed}; empty where it listed names alone
     * (CalDAV, Graph).
     */
    public final List<Event> bodies;

    /** Resource names removed since the cursor. */
    public final List<String> vanished;

    /** The next cursor, or null when the backend issued none. */
    public final String token;

    /**
     * True when the round listed the complete member set: an initial
     * round, or an expired cursor re-run as one.
     */
    public final boolean complete;

    public EventDelta(
            List<EventRef> changed,
            List<Event> bodies,
            List<String> vanished,
            String token,
            boolean complete) {
        this.changed = changed;
        this.bodies = bodies;
        this.vanished = vanished;
        this.token = token;
        this.complete = complete;
    }
}
