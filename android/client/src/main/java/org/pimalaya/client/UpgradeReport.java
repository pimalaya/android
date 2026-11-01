package org.pimalaya.client;

import org.json.JSONObject;

/**
 * What one upgrade to the full detail tier did, counted.
 *
 * <p>Mirrors rs/offline/wire.rs {@code UpgradeReportJson}.
 *
 * @param deduped bodies the store already held under the same link id
 * @param fetched bodies fetched from the remote
 * @param overruled bodies a policy did not adopt, kept as retained items
 * @param upgraded placements raised to the full tier
 */
public record UpgradeReport(int deduped, int fetched, int overruled, int upgraded) {
    static UpgradeReport from(JSONObject json) {
        return new UpgradeReport(
                Json.integer(json, "deduped"),
                Json.integer(json, "fetched"),
                Json.integer(json, "overruled"),
                Json.integer(json, "upgraded"));
    }
}
