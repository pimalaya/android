package org.pimalaya.client;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * The N of the field model, each component's values space-joined
 * (rs/contact/fields.rs {@code ContactName} 172). A key the model leaves
 * out reads as empty.
 *
 * @param family the family names
 * @param given the given names
 * @param middle the additional names
 * @param prefix the honorific prefixes
 * @param suffix the honorific suffixes
 */
public record ContactName(
        String family, String given, String middle, String prefix, String suffix) {
    static ContactName from(JSONObject name) {
        return new ContactName(
                name.optString("family"),
                name.optString("given"),
                name.optString("middle"),
                name.optString("prefix"),
                name.optString("suffix"));
    }

    JSONObject toJson() throws JSONException {
        JSONObject name = new JSONObject();
        name.put("family", family);
        name.put("given", given);
        name.put("middle", middle);
        name.put("prefix", prefix);
        name.put("suffix", suffix);
        return name;
    }
}
