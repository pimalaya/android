package org.pimalaya.client;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * One replica of a {@link ReplicaPool} (rs/contact/group.rs
 * {@code PoolReplica} 29). A null component is an absent key, read
 * bridge-side as empty.
 *
 * @param ref the replica reference
 * @param uid the vCard UID
 * @param name the display name
 * @param id the card id, standing in for a nameless card's name
 */
public record PoolReplica(String ref, String uid, String name, String id) {
    JSONObject toJson() {
        try {
            return new JSONObject().put("ref", ref).put("uid", uid).put("name", name).put("id", id);
        } catch (JSONException error) {
            throw new PimalayaException("Unwritable pool replica: " + error.getMessage());
        }
    }
}
