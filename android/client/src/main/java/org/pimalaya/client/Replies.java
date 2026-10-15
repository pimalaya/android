package org.pimalaya.client;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * The bridge reply envelope, its {@code error} thrown as a failure.
 *
 * <p>The failure is a {@link PimalayaException}.
 */
final class Replies {
    private Replies() {}

    /**
     * Parses an object reply, throwing its {@code error} with any HTTP status.
     */
    static JSONObject object(String json) {
        JSONObject reply = read(json);
        String error = reply.optString("error");
        if (!error.isEmpty()) {
            throw new PimalayaException(error, reply.has("status") ? reply.optInt("status") : null);
        }
        return reply;
    }

    /**
     * Parses an object reply and hands it back whole, failure included.
     *
     * <p>For the one caller that reads more of a failure than its
     * message: a submission says whether it was refused, and
     * {@link #object} would have thrown before anything could look.
     */
    static JSONObject read(String json) {
        if (json == null) {
            throw new PimalayaException("Internal error: no bridge reply");
        }
        try {
            return new JSONObject(json.trim());
        } catch (JSONException error) {
            throw new PimalayaException("Unreadable bridge reply: " + error.getMessage());
        }
    }

    /** Parses an array reply, surfacing the bridge's {@code error} field. */
    static JSONArray array(String json) {
        if (json == null) {
            throw new PimalayaException("Internal error: no bridge reply");
        }
        String trimmed = json.trim();

        if (trimmed.startsWith("{")) {
            // NOTE: an object where an array is expected is always an error.
            object(trimmed);
            throw new PimalayaException("Unreadable bridge reply: expected an array");
        }

        try {
            return new JSONArray(trimmed);
        } catch (JSONException error) {
            throw new PimalayaException("Unreadable bridge reply: " + error.getMessage());
        }
    }

    /** Whether a bridge reply is a failure, without parsing a large one. */
    static boolean failed(String reply) {
        return reply != null && reply.trim().startsWith("{\"error\"");
    }
}
