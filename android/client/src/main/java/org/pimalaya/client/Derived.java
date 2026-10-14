package org.pimalaya.client;

import org.json.JSONObject;

/**
 * What io-pimdir derives from one body.
 *
 * <p>Mirrors rs/ffi/offline.rs {@code DerivedReply} 292.
 *
 * @param linkId the identity the body is filed under
 * @param sortKey the key the body sorts by
 * @param summary the summary as the store's writes take it, opaque (the
 *     pimdir persistence format), read-only by contract; null if none
 */
public record Derived(String linkId, String sortKey, JSONObject summary) {
    static Derived from(JSONObject json) {
        return new Derived(
                Json.string(json, "linkId"),
                json.optString("sortKey", ""),
                json.optJSONObject("summary"));
    }
}
