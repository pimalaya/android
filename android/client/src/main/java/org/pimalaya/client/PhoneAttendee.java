package org.pimalaya.client;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * One {@code ATTENDEE} of the phone view, its parameters as written.
 *
 * <p>Mirrors rs/calendar/phone.rs {@code PhoneAttendee} 130. A key the view
 * leaves out reads as empty.
 *
 * @param email the address without {@code mailto:}
 * @param name the {@code CN} parameter
 * @param role the {@code ROLE} parameter
 * @param cutype the {@code CUTYPE} parameter
 * @param partstat the {@code PARTSTAT} parameter
 */
public record PhoneAttendee(
        String email, String name, String role, String cutype, String partstat) {
    static PhoneAttendee from(JSONObject attendee) {
        return new PhoneAttendee(
                attendee.optString("email"),
                attendee.optString("name"),
                attendee.optString("role"),
                attendee.optString("cutype"),
                attendee.optString("partstat"));
    }

    JSONObject toJson() throws JSONException {
        JSONObject json = new JSONObject();
        json.put("email", email);
        json.put("name", name);
        json.put("role", role);
        json.put("cutype", cutype);
        json.put("partstat", partstat);
        return json;
    }
}
