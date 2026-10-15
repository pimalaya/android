package org.pimalaya.client;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * One alarm of the phone view.
 *
 * <p>Mirrors rs/calendar/phone.rs {@code PhoneAlarm} 119: minutes before the
 * start, or the absolute time it was written at.
 *
 * @param minutes minutes before the start, negative for after; null when
 *     absolute
 * @param absolute a UTC stamp, on a component that does not recur; else null
 */
public record PhoneAlarm(Long minutes, String absolute) {
    static PhoneAlarm from(JSONObject alarm) {
        return new PhoneAlarm(
                alarm.isNull("minutes") ? null : alarm.optLong("minutes"),
                Json.optString(alarm, "absolute"));
    }

    JSONObject toJson() throws JSONException {
        JSONObject json = new JSONObject();
        if (minutes != null) {
            json.put("minutes", minutes);
        }
        if (absolute != null) {
            json.put("absolute", absolute);
        }
        return json;
    }
}
