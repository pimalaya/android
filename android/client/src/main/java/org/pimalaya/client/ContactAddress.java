package org.pimalaya.client;

import java.util.List;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * One ADR entry of the field model.
 *
 * <p>Mirrors rs/contact/fields.rs {@code ContactAddress} 115. A key the model
 * leaves out reads as empty.
 *
 * @param city the locality
 * @param country the country name
 * @param ext the extended address
 * @param pobox the post office box
 * @param postcode the postal code
 * @param pref whether the entry is preferred
 * @param region the region
 * @param street the street address, one line per street component
 * @param types the lowercase TYPE values, pref excluded
 */
public record ContactAddress(
        String city,
        String country,
        String ext,
        String pobox,
        String postcode,
        boolean pref,
        String region,
        String street,
        List<String> types) {
    static ContactAddress from(JSONObject entry) {
        return new ContactAddress(
                entry.optString("city"),
                entry.optString("country"),
                entry.optString("ext"),
                entry.optString("pobox"),
                entry.optString("postcode"),
                entry.optBoolean("pref"),
                entry.optString("region"),
                entry.optString("street"),
                List.copyOf(Json.strings(entry.optJSONArray("types"))));
    }

    JSONObject toJson() throws JSONException {
        JSONObject entry = new JSONObject();
        entry.put("city", city);
        entry.put("country", country);
        entry.put("ext", ext);
        entry.put("pobox", pobox);
        entry.put("postcode", postcode);
        entry.put("pref", pref);
        entry.put("region", region);
        entry.put("street", street);
        entry.put("types", new JSONArray(types));
        return entry;
    }
}
