package org.pimalaya.client;

import org.json.JSONObject;

/**
 * The calendar object a pure event verb answers.
 *
 * <p>Mirrors rs/ffi/calendar.rs {@code IcalReply} 603.
 *
 * @param ical the iCalendar text, null when none is left
 */
record EventIcal(String ical) {
    static EventIcal from(JSONObject reply) {
        return new EventIcal(Json.optString(reply, "ical"));
    }
}
