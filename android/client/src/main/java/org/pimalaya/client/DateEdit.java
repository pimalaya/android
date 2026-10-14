package org.pimalaya.client;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * One date an {@link EventEdit} writes.
 *
 * <p>Mirrors rs/calendar/edit.rs {@code DateEdit} 83. A null component is an
 * absent key.
 *
 * @param time the new civil value, {@code YYYYMMDD} or {@code YYYYMMDDTHHMMSS};
 *     empty removes the property
 * @param kind what the value is relative to; null keeps the replaced
 *     property's, {@code TZID} and {@code VALUE} included
 * @param tzid a zoned value's {@code TZID}, empty for none; null with a null
 *     kind
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
