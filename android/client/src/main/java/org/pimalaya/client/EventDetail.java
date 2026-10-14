package org.pimalaya.client;

import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * One calendar component read whole, for the page that shows it.
 *
 * <p>Mirrors rs/calendar/read.rs {@code EventDetail} 30: what RFC 5545 lets a
 * VEVENT, VTODO or VJOURNAL carry in one shared shape, the properties of one
 * kind left empty by the other two, since the page differs only by sections.
 *
 * @param component which of the three this is
 * @param uid {@code UID}, the identity every replica of this shares
 * @param summary its {@code SUMMARY}
 * @param description its {@code DESCRIPTION}
 * @param location its {@code LOCATION}
 * @param url its {@code URL}
 * @param status {@code STATUS} as written, its vocabulary per component
 * @param categories {@code CATEGORIES}, comma separated
 * @param start {@code DTSTART}, or a to-do's placing {@code DUE}; else null
 * @param end {@code DTEND}, null when absent; only an event has one
 * @param due {@code DUE}, null when absent; only a to-do has one
 * @param completed {@code COMPLETED}, UTC, null when absent; to-do only
 * @param allDay whether the placing date is a DATE rather than a DATE-TIME
 * @param recurrence the series' {@code RRULE}, raw, empty when not repeating
 * @param priority its {@code PRIORITY}, as written
 * @param percentComplete {@code PERCENT-COMPLETE} as written; to-do only
 * @param organizer {@code ORGANIZER}, the address alone
 * @param attendees every {@code ATTENDEE}, in the object's order
 * @param created its {@code CREATED}, raw
 * @param lastModified its {@code LAST-MODIFIED}, raw
 * @param recurrenceId the read override's {@code RECURRENCE-ID}; null for a
 *     series or an entry that does not recur
 */
public record EventDetail(
        String component,
        String uid,
        String summary,
        String description,
        String location,
        String url,
        String status,
        String categories,
        EventTime start,
        EventTime end,
        EventTime due,
        EventTime completed,
        boolean allDay,
        String recurrence,
        String priority,
        String percentComplete,
        String organizer,
        List<Attendee> attendees,
        String created,
        String lastModified,
        EventTime recurrenceId) {
    /** The component's wire name: {@code VEVENT}. */
    public static final String EVENT = "VEVENT";

    /** The component's wire name: {@code VTODO}. */
    public static final String TODO = "VTODO";

    /** The component's wire name: {@code VJOURNAL}. */
    public static final String JOURNAL = "VJOURNAL";

    static EventDetail from(JSONObject reply) {
        JSONArray listed = reply.optJSONArray("attendees");
        List<Attendee> attendees = new ArrayList<>(listed == null ? 0 : listed.length());
        for (int index = 0; listed != null && index < listed.length(); index++) {
            attendees.add(Attendee.from(Json.object(listed, index)));
        }

        return new EventDetail(
                reply.optString("component"),
                reply.optString("uid"),
                reply.optString("summary"),
                reply.optString("description"),
                reply.optString("location"),
                reply.optString("url"),
                reply.optString("status"),
                reply.optString("categories"),
                EventTime.from(reply.optJSONObject("start")),
                EventTime.from(reply.optJSONObject("end")),
                EventTime.from(reply.optJSONObject("due")),
                EventTime.from(reply.optJSONObject("completed")),
                reply.optBoolean("allDay"),
                reply.optString("recurrence"),
                reply.optString("priority"),
                reply.optString("percentComplete"),
                reply.optString("organizer"),
                attendees,
                reply.optString("created"),
                reply.optString("lastModified"),
                EventTime.from(reply.optJSONObject("recurrenceId")));
    }

    /**
     * One attendee of a component: who, and where they stand.
     *
     * <p>Mirrors rs/calendar/read.rs {@code EventAttendee} 80.
     *
     * @param name the {@code CN} parameter, empty when there is none
     * @param address the calendar user address, {@code mailto:} stripped
     * @param status the {@code PARTSTAT} parameter, empty when there is none
     */
    public record Attendee(String name, String address, String status) {
        static Attendee from(JSONObject attendee) {
            return new Attendee(
                    attendee.optString("name"),
                    attendee.optString("address"),
                    attendee.optString("status"));
        }
    }
}
