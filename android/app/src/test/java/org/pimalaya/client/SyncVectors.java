package org.pimalaya.client;

import org.json.JSONObject;

/**
 * The bridge's seam of the sync vector runner (rs/ffi/vector.rs), which a
 * debug library alone holds: the offline verbs as {@link OfflineClient} runs
 * them, answering the run's events in place of its counts.
 *
 * <p>A reply is {@code {events, failed, parked?, refusal?, error?}}: {@code
 * failed} with its {@code refusal} when the engine failed the run, {@code
 * error} when its driver cut it short.
 */
public final class SyncVectors {
    static {
        // NOTE: the exports live in the library Native loads.
        Native.pimdirVersion();
    }

    private SyncVectors() {}

    /** {@code Native.offlineSync}, never full. */
    public static JSONObject sync(OfflineDriver driver, String collection, boolean content,
            String since, boolean scopeBound, boolean acceptAbsences) {
        return Replies.read(
                sync(driver, collection, false, content, since, scopeBound, acceptAbsences));
    }

    /** {@code Native.offlineUpgrade}. */
    public static JSONObject upgrade(
            OfflineDriver driver, String collection, java.util.List<String> handles,
            boolean content) {
        return Replies.read(
                upgrade(driver, collection, new org.json.JSONArray(handles).toString(), content));
    }

    /**
     * {@code Native.offlineMutate} of a mutation as the app spells it, with
     * the wire's {@code overrule} when {@code overrule}: an edit a rule of
     * the app's makes (pimdir SYNC §7), which no producer of the app states.
     */
    public static JSONObject mutate(
            OfflineDriver driver, String collection, Mutation mutation, boolean overrule) {
        try {
            JSONObject wire = mutation.toJson();
            if (overrule) {
                wire.put("overrule", true);
            }
            return Replies.read(mutate(driver, collection, wire.toString()));
        } catch (org.json.JSONException unwritable) {
            throw new IllegalStateException(unwritable);
        }
    }

    /**
     * What a scripted remote names a member of {@code kind} by, from its
     * fixture and what its case states of it: {@code {linkId, sortKey,
     * summary?}}.
     */
    public static JSONObject named(String kind, JSONObject stated, byte[] body) {
        return Replies.object(named(kind, stated.toString(), body));
    }

    private static native String sync(OfflineDriver driver, String collection, boolean full,
            boolean content, String since, boolean scopeBound, boolean acceptAbsences);

    private static native String upgrade(
            OfflineDriver driver, String collection, String handles, boolean content);

    private static native String mutate(OfflineDriver driver, String collection, String mutation);

    private static native String named(String kind, String stated, byte[] body);
}
