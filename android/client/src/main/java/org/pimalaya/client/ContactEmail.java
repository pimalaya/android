package org.pimalaya.client;

import java.util.List;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * One EMAIL entry of the field model (rs/contact/fields.rs
 * {@code ContactEmail} 147). A key the model leaves out reads as empty.
 *
 * @param address the email address
 * @param pref whether the entry is preferred
 * @param types the lowercase TYPE values, pref excluded
 */
public record ContactEmail(String address, boolean pref, List<String> types) {
    static ContactEmail from(JSONObject entry) {
        return new ContactEmail(
                entry.optString("address"),
                entry.optBoolean("pref"),
                List.copyOf(Json.strings(entry.optJSONArray("types"))));
    }

    JSONObject toJson() throws JSONException {
        JSONObject entry = new JSONObject();
        entry.put("address", address);
        entry.put("pref", pref);
        entry.put("types", new JSONArray(types));
        return entry;
    }
}
