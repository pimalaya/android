package org.pimalaya.client;

import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
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
 * @param refusals the changes the remote refused for good, each once with
 *     its reason, those held for a refusal standing on their binding
 *     included (pimdir SYNC §5)
 * @param rejected local changes the remote turned down, pushed again later
 * @param unlisted bound members in scope a capped round, or a narrowed one
 *     handing no enumeration, did not list and kept
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
        List<Refusal> refusals,
        int rejected,
        int unlisted,
        int unreadable,
        int waiting) {
    /**
     * A change the remote refused for good.
     *
     * @param handle the handle the change was pushed for
     * @param reason the remote's text, scrubbed of credentials
     */
    public record Refusal(String handle, String reason) {}

    static SyncReport from(JSONObject json) {
        List<Refusal> refusals = new ArrayList<>();
        JSONArray listed = json.optJSONArray("refusals");
        for (int index = 0; listed != null && index < listed.length(); index++) {
            JSONObject refusal = Json.object(listed, index);
            refusals.add(
                    new Refusal(Json.string(refusal, "handle"), Json.string(refusal, "reason")));
        }
        return new SyncReport(
                Json.integer(json, "absent"),
                Json.integer(json, "bound"),
                Json.integer(json, "conflicts"),
                Json.integer(json, "overruled"),
                json.optBoolean("parked"),
                Json.integer(json, "pulled"),
                Json.integer(json, "pushed"),
                Json.integer(json, "refreshed"),
                List.copyOf(refusals),
                Json.integer(json, "rejected"),
                Json.integer(json, "unlisted"),
                Json.integer(json, "unreadable"),
                Json.integer(json, "waiting"));
    }
}
