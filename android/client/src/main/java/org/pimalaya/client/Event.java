package org.pimalaya.client;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * One calendar object as the server stores it, iCalendar text and all.
 *
 * <p>Mirrors rs/calendar.rs {@code Event} 101. Unparsed: a recurring event
 * renders per window, so {@link CalendarClient#expandEvent} decodes it.
 *
 * @param id resource name with any {@code .ics} stripped
 * @param etag entity tag guarding concurrent updates, or null
 * @param ical one VCALENDAR as text, read lossily where its bytes are not
 *     UTF-8: what the phone is shown
 * @param icalBase64 the bytes the server sent, as base64, where they are not
 *     UTF-8; null otherwise
 */
public record Event(String id, String etag, String ical, String icalBase64) {
    /** An object whose bytes are its text. */
    public Event(String id, String etag, String ical) {
        this(id, etag, ical, null);
    }

    static Event from(JSONObject event) {
        return new Event(
                Json.string(event, "id"),
                Json.optString(event, "etag"),
                Json.string(event, "ical"),
                Json.optString(event, "icalBase64"));
    }

    /** The bytes the server sent: what the store keeps and hashes. */
    public byte[] bytes() {
        return icalBase64 == null
                ? ical.getBytes(StandardCharsets.UTF_8)
                : Base64.getDecoder().decode(icalBase64);
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
