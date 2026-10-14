package org.pimalaya.client;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * The GENDER of the field model.
 *
 * <p>Mirrors rs/contact/fields.rs {@code ContactGender} 161. A key the model
 * leaves out reads as empty.
 *
 * @param identity the free-text gender identity
 * @param sex the sex code (RFC 6350 6.2.7)
 */
public record ContactGender(String identity, String sex) {
    static ContactGender from(JSONObject gender) {
        return new ContactGender(gender.optString("identity"), gender.optString("sex"));
    }

    JSONObject toJson() throws JSONException {
        JSONObject gender = new JSONObject();
        gender.put("identity", identity);
        gender.put("sex", sex);
        return gender;
    }
}
