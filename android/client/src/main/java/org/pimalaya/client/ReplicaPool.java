package org.pimalaya.client;

import java.util.List;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * The replica pool the merged view groups.
 *
 * <p>Mirrors rs/contact/group.rs {@code ReplicaPool} 14.
 *
 * @param replicas the replicas, in pool order
 * @param links natural key to the cluster the user linked it into; a null
 *     cluster leaves the key unbound
 * @param detached the refs of the replicas the user detached
 */
public record ReplicaPool(
        List<PoolReplica> replicas, Map<String, String> links, List<String> detached) {
    JSONObject toJson() {
        try {
            JSONArray rows = new JSONArray();
            for (PoolReplica replica : replicas) {
                rows.put(replica.toJson());
            }
            return new JSONObject()
                    .put("replicas", rows)
                    .put("links", new JSONObject(links))
                    .put("detached", new JSONArray(detached));
        } catch (JSONException error) {
            throw new PimalayaException("Unwritable replica pool: " + error.getMessage());
        }
    }
}
