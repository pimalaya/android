package org.pimalaya.client;

import org.json.JSONObject;

/**
 * One rendered instance of a calendar component: one agenda row.
 *
 * <p>Mirrors rs/calendar/expand.rs {@code Occurrence} 50. Times are civil
 * ({@link EventTime}): RFC 5545 expands on wall-clock time, so 09:00 recurs
 * at 09:00 in its zone.
 *
 * @param component its wire name: {@code VEVENT}, {@code VTODO} or
 *     {@code VJOURNAL}
 * @param start when it starts, as its series placed it or an override moved it
 * @param end when it ends
 * @param recurrenceId the {@code RECURRENCE-ID} naming this instance; null for
 *     an entry that does not recur
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
