package org.pimalaya.client;

import org.json.JSONObject;

/**
 * The ETag an update answered.
 *
 * <p>Mirrors rs/ffi/calendar.rs {@code EtagReply} 609. Read as a JSON
 * string: Google's and Graph's ETags carry quotes of their own, which a
 * listing names unescaped.
 *
 * @param etag entity tag of the new revision, or null
 */
record EventEtag(String etag) {
    static EventEtag from(JSONObject reply) {
        return new EventEtag(Json.optString(reply, "etag"));
    }
}
