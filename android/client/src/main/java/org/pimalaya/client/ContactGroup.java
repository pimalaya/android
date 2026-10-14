package org.pimalaya.client;

import java.util.List;
import org.json.JSONObject;

/**
 * One merged contact of the grouped pool (rs/contact/group.rs
 * {@code ContactGroup} 53).
 *
 * @param key the group key: a cluster, {@code uid\0<uid>} or
 *     {@code ref\0<ref>}
 * @param replicas the indexes of the members in the pool, in pool order
 */
public record ContactGroup(String key, List<Integer> replicas) {
    static ContactGroup from(JSONObject reply) {
        return new ContactGroup(
                reply.optString("key"), List.copyOf(Json.integers(reply.optJSONArray("replicas"))));
    }
}
