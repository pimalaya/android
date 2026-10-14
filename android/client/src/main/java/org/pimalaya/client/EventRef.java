package org.pimalaya.client;

import org.json.JSONObject;

/**
 * One member of an enumerated calendar: its resource name and ETag.
 *
 * <p>Mirrors rs/calendar.rs {@code EventRef} 138.
 *
 * @param id the resource name
 * @param etag entity tag guarding concurrent updates, or null
 */
public record EventRef(String id, String etag) {
    static EventRef from(JSONObject event) {
        return new EventRef(Json.string(event, "id"), Json.optString(event, "etag"));
    }
}
