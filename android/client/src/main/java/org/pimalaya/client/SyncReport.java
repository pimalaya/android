package org.pimalaya.client;

import org.json.JSONObject;

/**
 * What one sync of a collection did, counted.
 *
 * <p>Mirrors rs/offline/wire.rs {@code SyncReportJson}.
 *
 * @param absent the bound members a parked round's listing left out, 0 for
 *     a closed round
 * @param bound the bound members a parked round counted, 0 for a closed one
 * @param conflicts placements left conflicted
 * @param overruled bodies a policy did not adopt, kept as retained items
 * @param parked whether the round was parked, its last page finding most of
 *     its scope absent (pimdir SYNC §5)
 * @param pulled remote changes applied to the store
 * @param pushed local changes the remote accepted
 * @param refreshed placements whose remote revision was re-read
 * @param rejected local changes the remote refused
 * @param unreadable changes withheld, their body missing or failing its name
 * @param waiting local changes left staged for a later pass
 */
public record SyncReport(
        int absent,
        int bound,
        int conflicts,
        int overruled,
        boolean parked,
        int pulled,
        int pushed,
        int refreshed,
        int rejected,
        int unreadable,
        int waiting) {
    static SyncReport from(JSONObject json) {
        return new SyncReport(
                Json.integer(json, "absent"),
                Json.integer(json, "bound"),
                Json.integer(json, "conflicts"),
                Json.integer(json, "overruled"),
                json.optBoolean("parked"),
                Json.integer(json, "pulled"),
                Json.integer(json, "pushed"),
                Json.integer(json, "refreshed"),
                Json.integer(json, "rejected"),
                Json.integer(json, "unreadable"),
                Json.integer(json, "waiting"));
    }
}
