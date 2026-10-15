package org.pimalaya.client;

import org.json.JSONObject;

/**
 * A series split at one occurrence: the old series ended, a new one begun.
 *
 * <p>Mirrors rs/ffi/calendar.rs {@code SplitReply} 595.
 *
 * @param master the series' object, ended before the occurrence
 * @param series the new series' object, null when the occurrence was first
 */
public record EventSplit(String master, String series) {
    static EventSplit from(JSONObject reply) {
        return new EventSplit(Json.string(reply, "master"), Json.optString(reply, "series"));
    }
}
