package org.pimalaya.client;

import org.json.JSONObject;

/**
 * A calendar listed from the server (rs/calendar.rs {@code Calendar} 77).
 *
 * @param id the last non-empty path segment of the collection URL
 * @param name the display name, falling back to the id
 * @param url absolute collection URL, the target of every event operation
 * @param description free-form description, or null
 * @param color display colour, or null
 * @param role pimdir's role: {@link #DEFAULT} for the default calendar (JMAP
 *     {@code isDefault}, Graph's default, Google's primary), else empty
 * @param writable whether events may be written; true where the source is mute
 * @param vtodo whether its server takes a to-do there: false when its CalDAV
 *     {@code supported-calendar-component-set} names components but not
 *     {@code VTODO}, true when it names {@code VTODO}, null when it states
 *     none or the backend has no such set
 */
public record Calendar(
        String id,
        String name,
        String url,
        String description,
        String color,
        String role,
        boolean writable,
        Boolean vtodo) {
    /** The role a source gives the calendar it writes to when none is named. */
    public static final String DEFAULT = "default";

    /** A calendar stating no component set. */
    public Calendar(
            String id,
            String name,
            String url,
            String description,
            String color,
            String role,
            boolean writable) {
        this(id, name, url, description, color, role, writable, null);
    }

    static Calendar from(JSONObject calendar) {
        return new Calendar(
                Json.string(calendar, "id"),
                Json.string(calendar, "name"),
                Json.string(calendar, "url"),
                Json.optString(calendar, "description"),
                Json.optString(calendar, "color"),
                calendar.optString("role"),
                calendar.optBoolean("writable", true),
                calendar.has("vtodo") ? calendar.optBoolean("vtodo") : null);
    }
}
