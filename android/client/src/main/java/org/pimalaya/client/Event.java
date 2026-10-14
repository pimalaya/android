package org.pimalaya.client;

import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * One calendar object as the server stores it, iCalendar text and all
 * (rs/calendar.rs {@code Event} 101).
 *
 * <p>Deliberately unparsed: what a recurring event renders as depends on
 * the window being shown, so decoding happens at
 * {@link CalendarClient#expandEvent} where that window is known.
 *
 * @param id resource name with any {@code .ics} stripped
 * @param etag entity tag guarding concurrent updates, or null
 * @param ical raw iCalendar text, one VCALENDAR
 */
public record Event(String id, String etag, String ical) {
    static Event from(JSONObject event) {
        return new Event(
                Json.string(event, "id"),
                Json.optString(event, "etag"),
                Json.string(event, "ical"));
    }

    /** The events of a reply's array, none for a missing one. */
    static List<Event> list(JSONArray reply) {
        if (reply == null) {
            return List.of();
        }
        List<Event> events = new ArrayList<>(reply.length());
        for (int index = 0; index < reply.length(); index++) {
            events.add(from(Json.object(reply, index)));
        }
        return events;
    }
}
