package org.pimalaya.client;

import org.json.JSONObject;

/**
 * The search index of pimdir SEARCH.md, index.db, in the app's own shape
 * ({@code pimalaya-android}: FTS4 on the platform's SQLite).
 *
 * <p>The bridge holds the index's schema, statements and logic; its owner
 * holds the file, one handle and the transactions, and runs the SQL the
 * bridge sends through {@link Steps}, never SQL of its own.
 */
public final class PimdirIndex {
    private PimdirIndex() {}

    /**
     * index.db's owner as the bridge reaches it: steps of SQL run in order
     * on its handle, inside the transaction the caller holds.
     *
     * <p>{@code {"steps": [{"sql", "args", "read"}]}} in, {@code {"rows":
     * [[row, ...], ...]}} out, one entry per step, a write's empty; {@code
     * {"error"}} aborts the call.
     */
    public interface Steps {
        String serve(String request);
    }

    /**
     * A statement of the index its owner runs itself, its parameters
     * numbered: {@code ATTACH_STORE} (the store's path bound), {@code
     * BEGIN_RUN} and {@code COMMIT_RUN}, a run's deferred transaction.
     */
    public static String statement(String name) {
        return Native.pimdirIndexStatement(name);
    }

    /**
     * What index.db holds (SEARCH §2): {@code held} is {@code empty},
     * {@code current}, {@code rebuild} (with a {@code reason}) or {@code
     * refused} (with a {@code detail}).
     */
    public static JSONObject held(Steps index) {
        return Replies.object(Native.pimdirIndexHeld(index));
    }

    /** Builds the schema and {@code index_meta} of an empty index.db. */
    public static void init(Steps index) {
        Replies.object(Native.pimdirIndexInit(index));
    }

    /**
     * One run of the refresh (SEARCH §4), at most {@code batch} rows of the
     * store's feed, inside the caller's transaction: {@code {cursor,
     * exhausted, folded, indexed, removed}}.
     */
    public static JSONObject refresh(
            Steps index, String blobs, long now, int batch, boolean missing) {
        return Replies.object(Native.pimdirIndexRefresh(index, blobs, now, batch, missing));
    }

    /**
     * Every hit of {@code query}, ordered {@code date} or {@code name}:
     * {@code {hits: [{account?, seq}]}}, or {@code {refused: {code, message,
     * start, end, expected, fields}}} for a query the language refuses.
     */
    public static JSONObject hits(Steps index, String query, String order, long now) {
        return Replies.object(Native.pimdirIndexHits(index, query, order, now));
    }

    /**
     * One page of {@code query}'s hits described, each with its placements
     * and a snippet of its body: {@code {hits, more}} or {@code {refused}}.
     */
    public static JSONObject search(
            Steps index, String blobs, String query, String order, int limit, int offset,
            long now) {
        return Replies.object(
                Native.pimdirIndexSearch(index, blobs, query, order, limit, offset, now));
    }
}
