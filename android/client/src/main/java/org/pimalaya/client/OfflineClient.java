package org.pimalaya.client;

import java.util.List;
import org.json.JSONArray;

/**
 * The io-pimdir engine verbs of the bridge (rs/ffi/offline.rs).
 *
 * <p>A sync, an upgrade or a mutation runs through an {@link OfflineDriver};
 * the static plan verbs take facts in and answer a decision.
 */
public class OfflineClient {
    /**
     * Reconciles a collection with its remote, the driver serving every yield.
     *
     * <p>With {@code full} the checkpoint is ignored and the whole remote
     * enumerated.
     */
    public SyncReport sync(OfflineDriver driver, String collection, boolean full) {
        return SyncReport.from(
                Replies.object(Native.offlineSync(driver, collection, full, true, "", true)));
    }

    /**
     * The same for immutable bodies (mail): only flags and membership push.
     *
     * <p>{@code since} is the account's {@code Date} floor (RFC 3339
     * {@code Z}), null for all mail; {@code scopeBound} whether the checkpoint
     * is bound to its scope (a Graph delta link, unlike a modseq or a state).
     */
    public SyncReport syncImmutable(
            OfflineDriver driver, String collection, String since, boolean scopeBound) {
        return SyncReport.from(
                Replies.object(
                        Native.offlineSync(
                                driver,
                                collection,
                                false,
                                false,
                                since == null ? "" : since,
                                scopeBound)));
    }

    /** Raises handles to the full tier, bodies deduped by link id. */
    public UpgradeReport upgrade(OfflineDriver driver, String collection, List<String> handles) {
        return UpgradeReport.from(
                Replies.object(
                        Native.offlineUpgrade(
                                driver, collection, new JSONArray(handles).toString())));
    }

    /** Stages a local mutation; storage yields only, the remote untouched. */
    public void mutate(OfflineDriver driver, String collection, Mutation mutation) {
        Replies.object(Native.offlineMutate(driver, collection, mutation.toJson().toString()));
    }

    /**
     * Whether a 412-rejected push may retry unguarded.
     *
     * <p>The last enumerate must prove the handle unchanged (the CardDAV
     * If-Match quirk).
     */
    public static boolean retryUnguarded(RetryFacts facts) {
        return Replies.object(Native.offlineRetryUnguarded(facts.toJson().toString()))
                .optBoolean("retry");
    }

    /** Projects an account-wide delta (JMAP, Google) onto one book. */
    public static AccountSnapshot accountSnapshot(AccountSnapshotFacts facts) {
        return AccountSnapshot.from(
                Replies.object(Native.offlineAccountSnapshot(facts.toJson().toString())));
    }

    /** Plans one push change. */
    public static PushPlan pushPlan(PushPlanFacts facts) {
        return PushPlan.from(Replies.object(Native.offlinePushPlan(facts.toJson().toString())));
    }
}
