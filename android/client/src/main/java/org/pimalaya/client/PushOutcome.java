package org.pimalaya.client;

import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * One change's outcome of a batched push round (rs/contact/push.rs
 * {@code PushOutcome} 37).
 *
 * @param ref the correlation key of the change this outcome answers
 * @param accepted whether the server took the change; a rejected change is
 *     reconciled by the next sync instead of failing the round
 * @param id the server-assigned id of an accepted create, or null
 * @param etag the revision of the accepted write, or null where the backend
 *     has none
 * @param error what the server objected, on a rejected change, or null
 */
public record PushOutcome(String ref, boolean accepted, String id, String etag, String error) {
    static PushOutcome from(JSONObject reply) {
        return new PushOutcome(
                Json.string(reply, "ref"),
                reply.optBoolean("accepted"),
                Json.optString(reply, "id"),
                Json.optString(reply, "etag"),
                Json.optString(reply, "error"));
    }

    static List<PushOutcome> list(JSONArray reply) {
        List<PushOutcome> outcomes = new ArrayList<>(reply.length());
        for (int index = 0; index < reply.length(); index++) {
            outcomes.add(from(Json.object(reply, index)));
        }
        return outcomes;
    }
}
