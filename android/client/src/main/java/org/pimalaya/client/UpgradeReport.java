package org.pimalaya.client;

import org.json.JSONObject;

/**
 * What one upgrade to the full detail tier did, counted.
 *
 * <p>Mirrors rs/offline/wire.rs {@code UpgradeReportJson} 1026.
 *
 * @param deduped bodies the store already held under the same link id
 * @param fetched bodies fetched from the remote
 * @param upgraded placements raised to the full tier
 */
public record UpgradeReport(int deduped, int fetched, int upgraded) {
    static UpgradeReport from(JSONObject json) {
        return new UpgradeReport(
                Json.integer(json, "deduped"),
                Json.integer(json, "fetched"),
                Json.integer(json, "upgraded"));
    }
}
