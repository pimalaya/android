package org.pimalaya.client;

import java.util.List;
import org.json.JSONObject;

/**
 * What the app's store indexes a card under (rs/contact/fields.rs
 * {@code CardIndex} 812): the display fields the contacts list renders and
 * the normalized content hash linked replicas diverge by.
 *
 * @param email the first email address, empty when the card has none
 * @param emails every non-empty email address, in document order
 * @param hash the normalized content hash, 16 hex digits
 * @param info the list row's fallback line, maybe empty
 * @param name the display name to show, maybe empty
 * @param phone the first phone number, empty when the card has none
 * @param sortKey the key the store sorts the card under
 * @param summary the store's {@code text/vcard} summary, null when
 *     underivable; the pimdir persistence format, read-only
 * @param uid the card's UID, empty when absent
 */
public record CardIndex(
        String email,
        List<String> emails,
        String hash,
        String info,
        String name,
        String phone,
        String sortKey,
        JSONObject summary,
        String uid) {
    static CardIndex from(JSONObject reply) {
        return new CardIndex(
                reply.optString("email"),
                List.copyOf(Json.strings(reply.optJSONArray("emails"))),
                reply.optString("hash"),
                reply.optString("info"),
                reply.optString("name"),
                reply.optString("phone"),
                reply.optString("sortKey"),
                reply.optJSONObject("summary"),
                reply.optString("uid"));
    }
}
