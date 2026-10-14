package org.pimalaya.client;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * One date an {@link EventEdit} writes (rs/calendar/edit.rs
 * {@code DateEdit} 83). A null component is an absent key.
 *
 * @param time the new value, civil, {@code YYYYMMDD} or
 *     {@code YYYYMMDDTHHMMSS}; empty removes the property
 * @param kind what the value is relative to; null, it is relative to what
 *     the replaced property already was, {@code TZID} and {@code VALUE} kept
 * @param tzid the {@code TZID} of a zoned value, empty for none; null with
 *     a null kind
 */
public record DateEdit(String time, EventTimeKind kind, String tzid) {
    JSONObject toJson() throws JSONException {
        JSONObject date = new JSONObject();
        date.put("time", time);
        date.put("kind", kind == null ? null : kind.wire());
        date.put("tzid", tzid);
        return date;
    }
}
