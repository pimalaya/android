package org.pimalaya.client;

import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * A calendar object both sides edited since their base, merged: the
 * object the merge holds, and the collisions left for a person to settle
 * (rs/calendar/conflict.rs {@code EventMerge} 136).
 *
 * @param ical the merged object, each conflict holding its pre-filled side
 * @param resolved whether nothing is left to ask, so {@link #ical} is the
 *     resolution
 * @param conflicts the collisions left
 */
public record EventMerge(String ical, boolean resolved, List<Conflict> conflicts) {
    static EventMerge from(JSONObject reply) {
        JSONArray listed = reply.optJSONArray("conflicts");
        List<Conflict> conflicts = new ArrayList<>(listed == null ? 0 : listed.length());
        for (int index = 0; listed != null && index < listed.length(); index++) {
            conflicts.add(Conflict.from(Json.object(listed, index)));
        }

        return new EventMerge(Json.string(reply, "ical"), reply.optBoolean("resolved"), conflicts);
    }

    /**
     * One collision, as the entry page asks it (rs/calendar/conflict.rs
     * {@code EventConflict} 98).
     *
     * @param id what a resolution names it by
     * @param kind what the collision is
     * @param component the component it is on, {@code VCALENDAR} for the
     *     object's own properties
     * @param recurrenceId the occurrence it is on; null on a series or an
     *     entry that does not recur
     * @param field what it is: the edit object's key for a field the page
     *     edits ({@code summary}, {@code start}, {@code recurrence} for the
     *     rule), {@code when} for a start and an end that only together
     *     collide, {@code occurrence} for one occurrence whole,
     *     {@code entry} for the component whole, else the property's name as
     *     written
     * @param series the side that changed the series, on a recurrence
     *     conflict; else null
     * @param choices what each side holds, the pre-filled one first
     */
    public record Conflict(
            int id,
            EventConflictKind kind,
            String component,
            EventTime recurrenceId,
            String field,
            EventSide series,
            List<Choice> choices) {
        static Conflict from(JSONObject conflict) {
            JSONArray offered = conflict.optJSONArray("choices");
            List<Choice> choices = new ArrayList<>(offered == null ? 0 : offered.length());
            for (int at = 0; offered != null && at < offered.length(); at++) {
                choices.add(Choice.from(Json.object(offered, at)));
            }
            String series = Json.optString(conflict, "series");
            return new Conflict(
                    conflict.optInt("id"),
                    EventConflictKind.of(conflict.optString("kind")),
                    conflict.optString("component"),
                    EventTime.from(conflict.optJSONObject("recurrenceId")),
                    conflict.optString("field"),
                    series == null ? null : EventSide.of(series),
                    choices);
        }
    }

    /**
     * One side's value of a conflicted field (rs/calendar/conflict.rs
     * {@code EventChoice} 123).
     *
     * @param side whose value it is
     * @param value the value as a reader sees it, empty where the side holds
     *     none
     * @param time the value of a date, with its zone, the start on a span;
     *     null otherwise
     * @param end the end a span runs to; null on anything else
     */
    public record Choice(EventSide side, String value, EventTime time, EventTime end) {
        static Choice from(JSONObject choice) {
            return new Choice(
                    EventSide.of(choice.optString("side")),
                    choice.optString("value"),
                    EventTime.from(choice.optJSONObject("time")),
                    EventTime.from(choice.optJSONObject("end")));
        }
    }
}
