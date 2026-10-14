package org.pimalaya.client;

import java.util.List;
import org.json.JSONArray;

/**
 * The io-offline engine verbs of the bridge (rs/ffi/offline.rs): a sync,
 * an upgrade or a mutation driven through an {@link OfflineDriver}, and the
 * plan verbs, static, that take facts in and answer a decision.
 */
public class OfflineClient {
    /**
     * Reconciles a collection with its remote through the io-offline
     * engine, the driver servicing every storage and remote yield;
     * with {@code full} the checkpoint is ignored and the whole remote
     * is enumerated. Returns the sync report.
     */
    public SyncReport sync(OfflineDriver driver, String collection, boolean full) {
        return SyncReport.from(
                Replies.object(Native.offlineSync(driver, collection, full, true, "", true)));
    }

    /**
     * The same for a collection whose bodies never change, mail: only
     * flags and membership are pushed, so a body a read stored is never
     * mistaken for an edit.
     *
     * <p>{@code since} is the account's bound, the floor of the scope on
     * the {@code Date} header (RFC 3339 {@code Z}), null for all mail;
     * {@code scopeBound} whether the backend's checkpoint is bound to the
     * scope it was made under (a Graph delta link is, an IMAP modseq, a
     * Gmail history id and a JMAP state are not).
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

    /**
     * Raises the given handles to the full detail tier through the
     * io-offline engine (bodies deduped by link id against the store).
     * Returns the upgrade report.
     */
    public UpgradeReport upgrade(OfflineDriver driver, String collection, List<String> handles) {
        return UpgradeReport.from(
                Replies.object(
                        Native.offlineUpgrade(
                                driver, collection, new JSONArray(handles).toString(), true)));
    }

    /**
     * Stages a local mutation through the io-offline engine (storage
     * yields only, the remote is never touched).
     */
    public void mutate(OfflineDriver driver, String collection, Mutation mutation) {
        Replies.object(Native.offlineMutate(driver, collection, mutation.toJson().toString()));
    }

    /**
     * Whether a 412-rejected push may retry unguarded, the last
     * enumerate proving the handle unchanged (the CardDAV If-Match
     * quirk).
     */
    public static boolean retryUnguarded(RetryFacts facts) {
        return Replies.object(Native.offlineRetryUnguarded(facts.toJson().toString()))
                .optBoolean("retry");
    }

    /**
     * Projects an account-wide delta (JMAP, Google) onto one book's
     * enumerate.
     */
    public static AccountSnapshot accountSnapshot(AccountSnapshotFacts facts) {
        return AccountSnapshot.from(
                Replies.object(Native.offlineAccountSnapshot(facts.toJson().toString())));
    }

    /** Plans one push change. */
    public static PushPlan pushPlan(PushPlanFacts facts) {
        return PushPlan.from(Replies.object(Native.offlinePushPlan(facts.toJson().toString())));
    }
}
