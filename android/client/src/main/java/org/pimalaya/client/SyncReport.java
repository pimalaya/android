package org.pimalaya.client;

import org.json.JSONObject;

/**
 * What one sync of a collection did, counted (rs/offline/wire.rs
 * {@code SyncReportJson} 1002).
 *
 * @param conflicts placements left conflicted
 * @param pulled remote changes applied to the store
 * @param pushed local changes the remote accepted
 * @param refreshed placements whose remote revision was re-read
 * @param rejected local changes the remote refused
 * @param waiting local changes left staged for a later pass
 */
public record SyncReport(
        int conflicts, int pulled, int pushed, int refreshed, int rejected, int waiting) {
    static SyncReport from(JSONObject json) {
        return new SyncReport(
                Json.integer(json, "conflicts"),
                Json.integer(json, "pulled"),
                Json.integer(json, "pushed"),
                Json.integer(json, "refreshed"),
                Json.integer(json, "rejected"),
                Json.integer(json, "waiting"));
    }
}
