package org.pimalaya.client;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * One date or date-time of the phone view, civil, plus what it is civil in.
 *
 * <p>Mirrors rs/calendar/phone.rs {@code PhoneTime} 72: an {@link EventTime}
 * and, in an edit, the instant it names.
 *
 * @param time {@code YYYYMMDD} or {@code YYYYMMDDTHHMMSS}, never {@code Z}
 * @param kind what the time is relative to
 * @param tzid the {@code TZID}, verbatim; empty unless zoned
 * @param offset seconds east of UTC per the object's own {@code VTIMEZONE}
 *     at this time; null when the object does not define the zone
 * @param instant a UTC stamp an edit sends with a time in a zone only the
 *     object defines, which the bridge finds that zone's offset at; else null
 */
public record PhoneTime(
        String time, EventTimeKind kind, String tzid, Integer offset, String instant) {
    /** The time as an {@link EventTime}, its offset kept. */
    public EventTime eventTime() {
        return new EventTime(time, kind, tzid, offset);
    }

    /** One time of a view, null for a missing one. */
    static PhoneTime from(JSONObject time) {
        if (time == null) {
            return null;
        }
        return new PhoneTime(
                time.optString("time"),
                EventTimeKind.of(time.optString("kind")),
                time.optString("tzid"),
                time.isNull("offset") ? null : time.optInt("offset"),
                Json.optString(time, "instant"));
    }

    JSONObject toJson() throws JSONException {
        JSONObject json = new JSONObject();
        json.put("time", time);
        json.put("kind", kind.wire());
        json.put("tzid", tzid);
        if (offset != null) {
            json.put("offset", offset);
        }
        if (instant != null) {
            json.put("instant", instant);
        }
        return json;
    }
}
