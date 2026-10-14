package org.pimalaya.client;

import org.json.JSONObject;

/**
 * A series split at one occurrence: the series ended before it, and the
 * new series carrying the edit from it on (rs/ffi/calendar.rs
 * {@code SplitReply} 588).
 *
 * @param master the series' object, ended before the occurrence
 * @param series the new series' object, or null when the occurrence was
 *     the first
 */
public record EventSplit(String master, String series) {
    static EventSplit from(JSONObject reply) {
        return new EventSplit(Json.string(reply, "master"), Json.optString(reply, "series"));
    }
}
