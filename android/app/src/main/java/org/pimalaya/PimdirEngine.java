package org.pimalaya;

import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.pimalaya.client.OfflineDriver;
import org.pimalaya.client.PimalayaClient;
import org.pimalaya.client.PimalayaException;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * The half of an engine driver that has nothing to do with a domain: the
 * storage yields, the staged mutations and the wire shapes both ends
 * agree on.
 *
 * <p>io-pimdir's coroutines run in Rust and yield JSON envelopes; three
 * of the six are answered by the store and are the same answer whether
 * the collection holds cards, messages or calendar objects, because the
 * store is one schema. The three that differ are the remote's:
 * {@code enumerate} lists a collection's members, {@code fetch} reads
 * some of them, and {@code push} carries the staged writes out. A domain
 * driver is those three and nothing else.
 *
 * <p>Everything blocks; callers run it off the main thread.
 */
abstract class PimdirEngine implements OfflineDriver {
    /** The pimdir store's engine seam (placements, writes, conflicts). */
    protected final PimdirStorage offline;

    protected final PimalayaClient client;

    /**
     * Observes a pass's coarse steps for a progress display; steps fire on
     * the sync thread. Null when the pass runs headless.
     *
     * <p>The first three are every domain's, the last three the contacts
     * spoke's alone: only a book is reconciled against the phone.
     */
    interface Progress {
        /** Exchanging the spine with the server. */
        int STAGE_SERVER = 0;
        /** Downloading `count` bodies from the server. */
        int STAGE_DOWNLOAD = 1;
        /** Sending `count` changes to the server. */
        int STAGE_UPLOAD = 2;
        /** Reconciling with the phone's contacts. */
        int STAGE_PHONE = 3;
        /** Writing `count` contacts to the phone. */
        int STAGE_PROJECT = 4;
        /** Resolving `count` conflicts. */
        int STAGE_RESOLVE = 5;

        void step(int stage, int count);
    }

    /** The foreground pass's progress observer; null when headless. */
    Progress progress;

    protected void step(int stage, int count) {
        if (progress != null) {
            progress.step(stage, count);
        }
    }

    protected PimdirEngine(PimdirDb pimdir, PimalayaClient client) {
        this.offline = new PimdirStorage(pimdir);
        this.client = client;
    }

    /**
     * Raises every bodiless or stale placement of one collection to its
     * full body.
     *
     * <p>A pass of its own, and a required one wherever a reader needs the
     * body: a sync that finds the remote content changed drops the object
     * and leaves the placement below full, on purpose, because refetching
     * inside the merge would tie the exchange to the download. Nothing
     * else puts the body back, so a domain whose listing reads from the
     * store by body has to run this or a remote edit empties it.
     */
    protected void hydrate(String collection) {
        List<String> pending = offline.handlesBelowFull(collection);
        if (pending.isEmpty()) {
            return;
        }
        hydrating(collection, pending.size());
        Log.d(
                "pimalaya",
                "hydrate " + collection + " (" + pending.size() + " below full): "
                        + client.offlineUpgrade(this, collection, pending));
    }

    /** Announces a hydrate about to run, for a driver that reports one. */
    protected void hydrating(String collection, int count) {
        step(Progress.STAGE_DOWNLOAD, count);
    }

    /**
     * Where a pass's time goes: the debug log's numbers, page by page, and
     * what a test reads to tell the bridge's share from the store's.
     */
    static final class Clock {
        /** Nanoseconds in the remote's own call: the network and the connector. */
        long remote;

        /** Nanoseconds turning yields and replies into and out of JSON on this side. */
        long json;

        /** Nanoseconds in the engine between a reply and its next yield, its parse included. */
        long engine;

        /** Nanoseconds the store spent on loads and writes. */
        long store;

        /** Members the pages listed. */
        int listed;

        /** Pages listed. */
        int pages;

        void add(Clock other) {
            remote += other.remote;
            json += other.json;
            engine += other.engine;
            store += other.store;
            listed += other.listed;
            pages += other.pages;
        }

        @Override
        public String toString() {
            return listed + " listed in " + pages + " pages: remote " + remote / 1_000_000
                    + " ms, json " + json / 1_000_000 + " ms, engine " + engine / 1_000_000
                    + " ms, store " + store / 1_000_000 + " ms";
        }
    }

    /** The pass's clock, every page landed so far folded in. */
    final Clock clock = new Clock();

    /** The page under way, logged and folded into {@link #clock} at its write. */
    private Clock page = new Clock();

    /** When the last reply went back to the engine; 0 before the first. */
    private long returned;

    /**
     * Counts a remote call toward the page under way, for a driver timing
     * its network apart from reading what came back.
     */
    protected void remote(long nanos) {
        page.remote += nanos;
    }

    /** Counts the members one listed page carried toward the page under way. */
    protected void listed(int count) {
        page.listed += count;
        page.pages += 1;
    }

    @Override
    public String serve(String yieldJson) {
        long started = System.nanoTime();
        if (returned != 0) {
            page.engine += started - returned;
        }
        try {
            JSONObject yielded = new JSONObject(yieldJson);
            String op = yielded.getString("op");
            long parsed = System.nanoTime();
            page.json += parsed - started;
            String reply;
            switch (op) {
                case "load": {
                    JSONObject loaded =
                            offline.loadCollection(
                                    yielded.getString("collection"),
                                    yielded.optJSONObject("scope"));
                    long read = System.nanoTime();
                    page.store += read - parsed;
                    reply = loaded.toString();
                    page.json += System.nanoTime() - read;
                    break;
                }
                case "lookup":
                    reply = offline.lookupObjects(yielded.getJSONArray("links")).toString();
                    break;
                case "write":
                    applied(offline.applyWrites(yielded.getJSONArray("writes")));
                    page.store += System.nanoTime() - parsed;
                    reply = "{}";
                    if (page.pages > 0) {
                        Log.d("pimalaya", "page " + page);
                        clock.add(page);
                        page = new Clock();
                    }
                    break;
                case "enumerate": {
                    long remoteBefore = page.remote;
                    JSONObject listed = named(yielded, enumerate(yielded));
                    long done = System.nanoTime();
                    // NOTE: what of the call was not the remote's own was this
                    // side reading the reply.
                    page.json += (done - parsed) - (page.remote - remoteBefore);
                    reply = listed.toString();
                    page.json += System.nanoTime() - done;
                    break;
                }
                case "fetch":
                    reply = fetch(yielded).toString();
                    break;
                case "push":
                    reply = push(yielded).toString();
                    break;
                default:
                    reply = error("Unsupported engine yield " + op);
            }
            returned = System.nanoTime();
            return reply;
        } catch (Exception failure) {
            Log.w("pimalaya", "offline driver failed", failure);
            returned = System.nanoTime();
            return error(failure);
        }
    }

    /**
     * What a batch of storage writes turned out to be, one entry per
     * placement it touched. Ignored here: only a driver reporting a sync
     * to a user has anything to count.
     */
    protected void applied(JSONArray effects) throws JSONException {}

    /** The collection's member spine, as the reply to an enumerate yield. */
    protected abstract JSONObject enumerate(JSONObject yielded) throws JSONException;

    /**
     * Whether this driver's listings already name every member they carry
     * (SYNC §4), which a mail listing does off the headers it reads; a
     * driver answering handles and revisions alone leaves the naming to
     * {@link #named}.
     */
    protected boolean listingsNamed() {
        return false;
    }

    /** How many bodies one naming read asks for: the DAV multiget batch. */
    private static final int NAMING_BATCH = 64;

    /**
     * Names every member a page lists, so nothing reaches the store unnamed
     * (SYNC §4): a member this source already binds under a revision that
     * has not moved is named by the link id the store holds, and every
     * other one, new or changed, carries its body, read through this
     * driver's own {@link #fetch} 64 at a time, with the identity, summary
     * and sort key derived from it.
     *
     * <p>Where the meta of a DAV kind is its body, this is the read that
     * names it; it replaces the probe the store used to keep and the
     * upgrade that named it after the fact. A member whose body cannot be
     * read any more (gone between the listing and the read) is left out.
     */
    private JSONObject named(JSONObject yielded, JSONObject page) throws JSONException {
        JSONArray items = page.optJSONArray("items");
        if (listingsNamed() || items == null || items.length() == 0) {
            return page;
        }

        String collection = yielded.getString("collection");
        List<String> handles = new ArrayList<>(items.length());
        for (int index = 0; index < items.length(); index++) {
            handles.add(items.getJSONObject(index).getString("handle"));
        }
        java.util.Map<String, String[]> bound = offline.bound(collection, handles);

        List<String> unread = new ArrayList<>();
        for (int index = 0; index < items.length(); index++) {
            JSONObject item = items.getJSONObject(index);
            String[] held = bound.get(item.getString("handle"));
            String revision = item.isNull("revision") ? null : item.optString("revision", null);
            if (held != null && java.util.Objects.equals(held[1], revision)) {
                item.put("linkId", held[0]);
            } else {
                unread.add(item.getString("handle"));
            }
        }
        if (unread.isEmpty()) {
            return page;
        }

        hydrating(collection, unread.size());
        java.util.Map<String, JSONObject> bodies = new java.util.HashMap<>();
        for (int from = 0; from < unread.size(); from += NAMING_BATCH) {
            JSONObject asked = new JSONObject();
            asked.put("collection", collection);
            asked.put(
                    "handles",
                    new JSONArray(unread.subList(from, Math.min(unread.size(), from + NAMING_BATCH))));
            asked.put("tier", "full");
            JSONArray fetched = fetch(asked).optJSONArray("items");
            for (int index = 0; fetched != null && index < fetched.length(); index++) {
                JSONObject body = fetched.getJSONObject(index);
                bodies.put(body.getString("handle"), body);
            }
        }

        JSONArray kept = new JSONArray();
        for (int index = 0; index < items.length(); index++) {
            JSONObject item = items.getJSONObject(index);
            if (item.has("linkId")) {
                kept.put(item);
                continue;
            }
            JSONObject body = bodies.get(item.getString("handle"));
            if (body == null) {
                Log.w("pimalaya", "listed but unreadable, left out: " + item.getString("handle"));
                continue;
            }
            for (String field : new String[] {"linkId", "summary", "sortKey", "hash", "body"}) {
                if (body.has(field) && !body.isNull(field)) {
                    item.put(field, body.get(field));
                }
            }
            // NOTE: the listing's revision wins where it gave one, which is
            // the flavour the next listing compares against.
            if (!item.has("revision") && body.has("revision")) {
                item.put("revision", body.get("revision"));
            }
            kept.put(item);
        }
        page.put("items", kept);
        return page;
    }

    /** The named members at the asked tier, as the reply to a fetch yield. */
    protected abstract JSONObject fetch(JSONObject yielded) throws JSONException;

    /** The staged changes carried to the remote, as the reply to a push yield. */
    protected abstract JSONObject push(JSONObject yielded) throws JSONException;

    // ---- staged mutations -------------------------------------------------

    /**
     * Stages a flag set on one placement; the next sync pushes the
     * difference between it and the set the source last agreed on.
     */
    void mutateFlags(String collection, String handle, JSONArray flags) throws JSONException {
        JSONObject mutation = new JSONObject();
        mutation.put("op", "setFlags");
        mutation.put("handle", handle);
        mutation.put("flags", flags);
        client.offlineMutate(this, collection, mutation);
    }

    /**
     * Stages a removal: the placement becomes a tombstone the next sync
     * pushes, and a create that was never pushed is withdrawn instead.
     */
    void mutateRemove(String collection, String handle) throws JSONException {
        JSONObject mutation = new JSONObject();
        mutation.put("op", "remove");
        mutation.put("handle", handle);
        client.offlineMutate(this, collection, mutation);
    }

    /**
     * Stages a content edit on one placement; editing a conflicted
     * placement resolves it.
     */
    void mutateEdit(String collection, String handle, String body, JSONObject summary,
            String sortKey) throws JSONException {
        JSONObject mutation = new JSONObject();
        mutation.put("op", "edit");
        mutation.put("handle", handle);
        mutation.put("hash", PimdirHash.of(body));
        mutation.put("size", body.getBytes(StandardCharsets.UTF_8).length);
        mutation.put("body", body);
        mutation.put("summary", summary);
        // NOTE: an edit that changes what the key is derived from has to say
        // so, or the item keeps the position its old one gave it.
        mutation.put("sortKey", sortKey);
        client.offlineMutate(this, collection, mutation);
    }

    /**
     * Stages a locally authored item the remote has never seen; the next
     * sync pushes it as an append.
     */
    void mutateAdd(String collection, String linkId, String body, JSONArray flags,
            JSONObject summary, String sortKey) throws JSONException {
        JSONObject mutation = new JSONObject();
        mutation.put("op", "add");
        mutation.put("linkId", linkId);
        mutation.put("flags", flags);
        mutation.put("hash", PimdirHash.of(body));
        mutation.put("size", body.getBytes(StandardCharsets.UTF_8).length);
        mutation.put("body", body);
        mutation.put("summary", summary);
        mutation.put("sortKey", sortKey);
        client.offlineMutate(this, collection, mutation);
    }

    // ---- the wire shapes --------------------------------------------------

    /** One push result on the engine wire. */
    protected static JSONObject result(
            String handle, boolean accepted, String assigned, String revision)
            throws JSONException {
        JSONObject result = new JSONObject();
        result.put("handle", handle);
        result.put("accepted", accepted);
        if (assigned != null) {
            result.put("assigned", assigned);
        }
        if (revision != null) {
            result.put("revision", revision);
        }
        return result;
    }

    /** The strings of a JSON array, in order. */
    protected static List<String> stringsOf(JSONArray values) throws JSONException {
        List<String> strings = new ArrayList<>(values.length());
        for (int index = 0; index < values.length(); index++) {
            strings.add(values.getString(index));
        }
        return strings;
    }

    protected static String error(String message) {
        JSONObject reply = new JSONObject();
        try {
            reply.put("error", message);
        } catch (JSONException ignored) {
            return "{\"error\": \"driver failure\"}";
        }
        return reply.toString();
    }

    /**
     * A failure as the driver error reply, keeping the HTTP status a
     * bridge failure carries so it survives the round trip through the
     * engine (the sync entry points branch on it for the token refresh).
     */
    protected static String error(Exception failure) {
        String message = failure.getMessage();
        JSONObject reply = new JSONObject();
        try {
            reply.put("error", message == null ? failure.toString() : message);
            Integer status = status(failure);
            if (status != null) {
                reply.put("status", status);
            }
        } catch (JSONException ignored) {
            return "{\"error\": \"driver failure\"}";
        }
        return reply.toString();
    }

    protected static Integer status(Exception failure) {
        return failure instanceof PimalayaException
                ? ((PimalayaException) failure).status
                : null;
    }

    protected static boolean isPreconditionFailure(Exception failure) {
        return Integer.valueOf(412).equals(status(failure));
    }

    protected static boolean isGone(Exception failure) {
        return Integer.valueOf(404).equals(status(failure));
    }
}
