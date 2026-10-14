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
 * @param role what the source says the calendar is for, in pimdir's role
 *     vocabulary: {@link #DEFAULT} for its default calendar (JMAP
 *     {@code isDefault}, Graph's default calendar, Google's primary one),
 *     empty otherwise
 * @param writable whether the user may write events into it; true where
 *     the source says nothing
 */
public record Calendar(
        String id,
        String name,
        String url,
        String description,
        String color,
        String role,
        boolean writable) {
    /** The role a source gives the calendar it writes to when none is named. */
    public static final String DEFAULT = "default";

    static Calendar from(JSONObject calendar) {
        return new Calendar(
                Json.string(calendar, "id"),
                Json.string(calendar, "name"),
                Json.string(calendar, "url"),
                Json.optString(calendar, "description"),
                Json.optString(calendar, "color"),
                calendar.optString("role"),
                calendar.optBoolean("writable", true));
    }
}
