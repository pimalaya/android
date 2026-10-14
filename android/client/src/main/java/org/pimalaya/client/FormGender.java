package org.pimalaya.client;

import org.json.JSONObject;

/**
 * The gender row of the edit form's view support.
 *
 * <p>Mirrors rs/contact/form.rs {@code FormGender} 95. A key the reply leaves
 * out reads as zero or empty.
 *
 * @param identity the trimmed free-text identity
 * @param sexIndex the gender_types position of the sex code
 */
public record FormGender(String identity, int sexIndex) {
    static FormGender from(JSONObject gender) {
        return new FormGender(gender.optString("identity"), gender.optInt("sexIndex"));
    }
}
