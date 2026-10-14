package org.pimalaya.client;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * One card offered to the duplicate scan (rs/contact/dedup.rs
 * {@code DuplicateCard} 14). A null component is an absent key.
 *
 * @param ref the card's replica reference, echoed back in its group
 * @param vcard the card's vCard
 */
public record RefCard(String ref, String vcard) {
    JSONObject toJson() {
        try {
            return new JSONObject().put("ref", ref).put("vcard", vcard);
        } catch (JSONException error) {
            throw new PimalayaException("Unwritable duplicate card: " + error.getMessage());
        }
    }
}
