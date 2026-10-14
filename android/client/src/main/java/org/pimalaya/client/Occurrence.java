package org.pimalaya.client;

import org.json.JSONObject;

/**
 * One rendered instance of a calendar component: what a row in the
 * agenda is (rs/calendar/expand.rs {@code Occurrence} 50).
 *
 * <p>Its times are civil, with what they are relative to beside them
 * ({@link EventTime}), because RFC 5545 expands recurrence on wall-clock
 * time: an event at 09:00 recurs at 09:00 in its zone, whatever that
 * makes it in the reader's.
 *
 * @param component the component this came out of, as its wire name:
 *     {@code VEVENT}, {@code VTODO} or {@code VJOURNAL}. An agenda row
 *     shows all three the same way apart from its glyph.
 * @param start when it starts: where its series placed it, or an override
 *     moved it
 * @param end when it ends
 * @param recurrenceId which instance of its series it is, the value a
 *     {@code RECURRENCE-ID} naming it carries; null for an entry that does
 *     not recur
 * @param summary its {@code SUMMARY}
 * @param location its {@code LOCATION}
 * @param allDay whether the start is a DATE rather than a DATE-TIME
 */
public record Occurrence(
        String component,
        EventTime start,
        EventTime end,
        EventTime recurrenceId,
        String summary,
        String location,
        boolean allDay) {
    static Occurrence from(JSONObject occurrence) {
        return new Occurrence(
                Json.string(occurrence, "component"),
                EventTime.from(occurrence.optJSONObject("start")),
                EventTime.from(occurrence.optJSONObject("end")),
                EventTime.from(occurrence.optJSONObject("recurrenceId")),
                Json.string(occurrence, "summary"),
                Json.string(occurrence, "location"),
                occurrence.optBoolean("allDay"));
    }
}
