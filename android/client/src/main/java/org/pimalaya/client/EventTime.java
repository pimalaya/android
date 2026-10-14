package org.pimalaya.client;

import org.json.JSONObject;

/**
 * One date or date-time of a component, civil, plus what it is civil in.
 *
 * <p>Mirrors rs/calendar/zone.rs {@code EventTime} 48. Civil because RFC 5545
 * recurs on a start's wall clock; the app makes it an instant with the
 * platform's time-zone database, which the native library does not carry.
 *
 * @param time {@code YYYYMMDD} or {@code YYYYMMDDTHHMMSS}, never {@code Z}
 * @param kind what the time is relative to
 * @param tzid the {@code TZID}, verbatim; empty unless zoned
 * @param offset seconds east of UTC per the object's own {@code VTIMEZONE}
 *     at this time; null when the object does not define the zone
 */
public record EventTime(String time, EventTimeKind kind, String tzid, Integer offset) {
    /** Whether this is a day rather than a moment. */
    public boolean isDate() {
        return kind == EventTimeKind.DATE;
    }

    /** One time of a reply, null for a missing one. */
    static EventTime from(JSONObject time) {
        if (time == null) {
            return null;
        }
        return new EventTime(
                time.optString("time"),
                EventTimeKind.of(time.optString("kind")),
                time.optString("tzid"),
                time.isNull("offset") ? null : time.optInt("offset"));
    }
}
