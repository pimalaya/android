package org.pimalaya.client;

import java.util.List;

/**
 * A calendar object both sides edited since their base, merged: the
 * object the merge holds, and the collisions left for a person to settle.
 */
public final class EventMerge {
    /** A side of a conflict: the body staged here. */
    public static final String LOCAL = "local";

    /** A side of a conflict: the body the source holds. */
    public static final String REMOTE = "remote";

    /** A conflict of one field both sides changed differently. */
    public static final String FIELD = "field";

    /** A conflict of a series one side changed and an occurrence the other did. */
    public static final String RECURRENCE = "recurrence";

    /** The merged object, each conflict holding its pre-filled side. */
    public final String ical;

    /** Whether nothing is left to ask, so {@link #ical} is the resolution. */
    public final boolean resolved;

    public final List<Conflict> conflicts;

    public EventMerge(String ical, boolean resolved, List<Conflict> conflicts) {
        this.ical = ical;
        this.resolved = resolved;
        this.conflicts = conflicts;
    }

    /** One collision, as the entry page asks it. */
    public static final class Conflict {
        /** What a resolution names it by. */
        public final int id;

        /** {@link #FIELD} or {@link #RECURRENCE}. */
        public final String kind;

        /** The component it is on, {@code VCALENDAR} for the object's own properties. */
        public final String component;

        /** The occurrence it is on; null on a series or an entry that does not recur. */
        public final EventTime recurrenceId;

        /**
         * What it is: the edit object's key for a field the page edits
         * ({@code summary}, {@code start}, {@code recurrence} for the
         * rule), {@code when} for a start and an end that only together
         * collide, {@code occurrence} for one occurrence whole, {@code entry}
         * for the component whole, else the property's name as written.
         */
        public final String field;

        /** The side that changed the series, on a recurrence conflict; else null. */
        public final String series;

        /** What each side holds, the pre-filled one first. */
        public final List<Choice> choices;

        public Conflict(
                int id,
                String kind,
                String component,
                EventTime recurrenceId,
                String field,
                String series,
                List<Choice> choices) {
            this.id = id;
            this.kind = kind;
            this.component = component;
            this.recurrenceId = recurrenceId;
            this.field = field;
            this.series = series;
            this.choices = choices;
        }
    }

    /** One side's value of a conflicted field. */
    public static final class Choice {
        /** {@link #LOCAL} or {@link #REMOTE}. */
        public final String side;

        /** The value as a reader sees it, empty where the side holds none. */
        public final String value;

        /** The value of a date, with its zone, the start on a span; null otherwise. */
        public final EventTime time;

        /** The end a span runs to; null on anything else. */
        public final EventTime end;

        public Choice(String side, String value, EventTime time, EventTime end) {
            this.side = side;
            this.value = value;
            this.time = time;
            this.end = end;
        }
    }
}
