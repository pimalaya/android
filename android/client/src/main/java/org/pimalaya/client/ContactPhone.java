package org.pimalaya.client;

import java.util.List;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * One TEL entry of the field model (rs/contact/fields.rs
 * {@code ContactPhone} 203). A key the model leaves out reads as empty.
 *
 * @param number the phone number, as entered
 * @param pref whether the entry is preferred
 * @param types the lowercase TYPE values, pref excluded
 */
public record ContactPhone(String number, boolean pref, List<String> types) {
    static ContactPhone from(JSONObject entry) {
        return new ContactPhone(
                entry.optString("number"),
                entry.optBoolean("pref"),
                List.copyOf(Json.strings(entry.optJSONArray("types"))));
    }

    JSONObject toJson() throws JSONException {
        JSONObject entry = new JSONObject();
        entry.put("number", number);
        entry.put("pref", pref);
        entry.put("types", new JSONArray(types));
        return entry;
    }
}
