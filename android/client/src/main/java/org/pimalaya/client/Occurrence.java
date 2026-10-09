package org.pimalaya.client;

/**
 * One rendered instance of a calendar component: what a row in the
 * agenda is.
 *
 * <p>Its times are civil, with what they are relative to beside them
 * ({@link EventTime}), because RFC 5545 expands recurrence on wall-clock
 * time: an event at 09:00 recurs at 09:00 in its zone, whatever that
 * makes it in the reader's.
 */
public final class Occurrence {
    /**
     * The component this came out of, as its wire name: {@code VEVENT},
     * {@code VTODO} or {@code VJOURNAL}. An agenda row shows all three
     * the same way apart from its glyph.
     */
    public final String component;

    /** When it starts: where its series placed it, or an override moved it. */
    public final EventTime start;

    public final EventTime end;

    /**
     * Which instance of its series it is, the value a {@code RECURRENCE-ID}
     * naming it carries; null for an entry that does not recur.
     */
    public final EventTime recurrenceId;

    public final String summary;
    public final String location;
    public final boolean allDay;

    public Occurrence(
            String component,
            EventTime start,
            EventTime end,
            EventTime recurrenceId,
            String summary,
            String location,
            boolean allDay) {
        this.component = component;
        this.start = start;
        this.end = end;
        this.recurrenceId = recurrenceId;
        this.summary = summary;
        this.location = location;
        this.allDay = allDay;
    }
}
