package org.pimalaya.client;

import java.util.List;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * One RELATED entry of the field model (rs/contact/fields.rs
 * {@code ContactRelation} 217). A key the model leaves out reads as empty.
 *
 * @param pref whether the entry is preferred
 * @param types the lowercase TYPE values, pref excluded
 * @param value the related URI, or free text
 */
public record ContactRelation(boolean pref, List<String> types, String value) {
    static ContactRelation from(JSONObject entry) {
        return new ContactRelation(
                entry.optBoolean("pref"),
                List.copyOf(Json.strings(entry.optJSONArray("types"))),
                entry.optString("value"));
    }

    JSONObject toJson() throws JSONException {
        JSONObject entry = new JSONObject();
        entry.put("pref", pref);
        entry.put("types", new JSONArray(types));
        entry.put("value", value);
        return entry;
    }
}
