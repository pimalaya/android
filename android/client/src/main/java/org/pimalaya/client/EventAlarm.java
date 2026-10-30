package org.pimalaya.client;

import java.util.List;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * One alarm of an entry, whatever its action (RFC 5545 3.6.6).
 *
 * <p>Mirrors rs/calendar/alarm.rs {@code Alarm}: as read, or as an edit holds
 * it, a new one carrying no index.
 *
 * @param index its place among the master's alarms; null for a new one
 * @param action {@code ACTION}, uppercased: {@code DISPLAY}, {@code AUDIO},
 *     {@code EMAIL}, or another
 * @param minutes minutes before the start, negative for after, for a trigger
 *     off the start; else null
 * @param minutesBeforeEnd minutes before the end, for a trigger related to
 *     the end; else null
 * @param absolute an absolute trigger as written; else null
 * @param description what a display alarm shows, an email alarm's body
 * @param summary an email alarm's subject
 * @param attendees an email alarm's recipients, their addresses
 * @param repeat how many more times it rings
 * @param everyMinutes minutes between rings; null when it does not repeat
 */
public record EventAlarm(
        Integer index,
        String action,
        Long minutes,
        Long minutesBeforeEnd,
        String absolute,
        String description,
        String summary,
        List<String> attendees,
        long repeat,
        Long everyMinutes) {
    public static final String DISPLAY = "DISPLAY";
    public static final String AUDIO = "AUDIO";
    public static final String EMAIL = "EMAIL";

    /** A new alarm of {@code action}, {@code minutes} before the start. */
    public static EventAlarm before(
            String action,
            long minutes,
            String description,
            String summary,
            List<String> attendees) {
        return new EventAlarm(
                null, action, minutes, null, null, description, summary, attendees, 0, null);
    }

    /** This alarm ringing {@code minutes} before the start instead. */
    public EventAlarm at(long minutes) {
        return new EventAlarm(
                index, action, minutes, null, null, description, summary, attendees, repeat,
                everyMinutes);
    }

    /** This alarm as {@code action}, with what that action carries. */
    public EventAlarm as(
            String action, String description, String summary, List<String> attendees) {
        return new EventAlarm(
                index, action, minutes, minutesBeforeEnd, absolute, description, summary,
                attendees, repeat, everyMinutes);
    }

    static EventAlarm from(JSONObject alarm) {
        return new EventAlarm(
                alarm.isNull("index") ? null : alarm.optInt("index"),
                alarm.optString("action"),
                alarm.isNull("minutes") ? null : alarm.optLong("minutes"),
                alarm.isNull("minutesBeforeEnd") ? null : alarm.optLong("minutesBeforeEnd"),
                Json.optString(alarm, "absolute"),
                alarm.optString("description"),
                alarm.optString("summary"),
                List.copyOf(Json.strings(alarm.optJSONArray("attendees"))),
                alarm.optLong("repeat"),
                alarm.isNull("everyMinutes") ? null : alarm.optLong("everyMinutes"));
    }

    JSONObject toJson() throws JSONException {
        JSONObject json = new JSONObject();
        json.put("index", index == null ? JSONObject.NULL : index);
        json.put("action", action);
        json.put("minutes", minutes == null ? JSONObject.NULL : minutes);
        json.put("minutesBeforeEnd", minutesBeforeEnd == null ? JSONObject.NULL : minutesBeforeEnd);
        json.put("absolute", absolute == null ? JSONObject.NULL : absolute);
        json.put("description", description);
        json.put("summary", summary);
        json.put("attendees", new JSONArray(attendees));
        json.put("repeat", repeat);
        json.put("everyMinutes", everyMinutes == null ? JSONObject.NULL : everyMinutes);
        return json;
    }
}
