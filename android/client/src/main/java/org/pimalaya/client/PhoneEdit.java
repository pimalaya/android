package org.pimalaya.client;

import java.util.List;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * An edit of the phone view, and what patching it needs besides.
 *
 * <p>Mirrors rs/calendar/phone.rs {@code PhoneEdit} 187.
 *
 * @param event the edited view: only fields differing from the projection
 *     change
 * @param stamp now, a UTC stamp, for {@code DTSTAMP} and
 *     {@code LAST-MODIFIED}
 * @param addresses the user's addresses, which say whether they organize the
 *     event
 * @param vtimezones the {@code VTIMEZONE} of each zone the edit may write a
 *     time in
 */
public record PhoneEdit(
        PhoneEvent event, String stamp, List<String> addresses, List<String> vtimezones) {
    JSONObject toJson() {
        try {
            JSONObject json = new JSONObject();
            json.put("event", event.toJson());
            json.put("stamp", stamp);
            json.put("addresses", new JSONArray(addresses));
            json.put("vtimezones", new JSONArray(vtimezones));
            return json;
        } catch (JSONException error) {
            throw new PimalayaException("Unwritable phone edit: " + error.getMessage());
        }
    }
}
