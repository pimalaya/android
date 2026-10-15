package org.pimalaya.pimdir;

import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.pimalaya.client.Clients;
import org.pimalaya.client.Flags;
import org.pimalaya.client.Mutation;
import org.pimalaya.client.OfflineDriver;
import org.pimalaya.client.PimalayaException;

/** A driver's storage half: load, lookup and write yields, staged edits. */
public abstract class Stager implements OfflineDriver {
    /** The pimdir store's engine seam (placements, writes, conflicts). */
    public final PimdirStorage offline;

    /** The bridge's domain clients. */
    protected final Clients clients;

    /**
     * The store's locks ({@link PimdirDb#locks()}).
     *
     * <p>Its one writer guards every storage yield.
     */
    public final Locks locks;

    /** A stager over {@code pimdir}, the bridge through {@code clients}. */
    protected Stager(PimdirDb pimdir, Clients clients) {
        this.offline = new PimdirStorage(pimdir);
        this.clients = clients;
        this.locks = pimdir.locks();
    }

    /**
     * The bodies the store holds under some link ids, replying to a lookup.
     *
     * <p>Asked by an upgrade before fetching ({@link
     * PimdirStorage#lookupObjects}); a driver whose link ids name nothing
     * outside its collection answers fewer.
     */
    protected JSONObject lookup(JSONArray links) throws JSONException {
        return offline.lookupObjects(links);
    }

    /**
     * Where a pass's time goes, page by page.
     *
     * <p>The debug log's numbers, and what tests read to split bridge from
     * store.
     */
    public static final class Clock {
        /** Nanoseconds in the remote's own call: network and connector. */
        long remote;

        /** Nanoseconds turning yields and replies into and out of JSON. */
        long json;

        /** Nanoseconds in the engine from a reply to its next yield. */
        long engine;

        /** Nanoseconds the store spent on loads and writes. */
        long store;

        /** Members the pages listed. */
        public int listed;

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
    public final Clock clock = new Clock();

    /** The page under way, folded into {@link #clock} at its write. */
    Clock page = new Clock();

    /** When the last reply went back to the engine; 0 before the first. */
    private long returned;

    /** Counts a remote call's nanoseconds toward the page under way. */
    protected void remote(long nanos) {
        page.remote += nanos;
    }

    /** Nanoseconds spent on the network so far, the page under way included. */
    public long remoteSoFar() {
        return clock.remote + page.remote;
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
                    JSONObject loaded;
                    synchronized (locks.store) {
                        long began = System.nanoTime();
                        loaded =
                                offline.loadCollection(
                                        yielded.getString("collection"),
                                        yielded.optJSONObject("scope"));
                        page.store += System.nanoTime() - began;
                    }
                    long read = System.nanoTime();
                    reply = loaded.toString();
                    page.json += System.nanoTime() - read;
                    break;
                }
                case "lookup":
                    synchronized (locks.store) {
                        reply = lookup(yielded.getJSONArray("links")).toString();
                    }
                    break;
                case "write":
                    synchronized (locks.store) {
                        long began = System.nanoTime();
                        applied(offline.applyWrites(yielded.getJSONArray("writes")));
                        page.store += System.nanoTime() - began;
                    }
                    reply = "{}";
                    if (page.pages > 0) {
                        Log.d("pimalaya", "page " + page);
                        clock.add(page);
                        page = new Clock();
                    }
                    break;
                default:
                    reply = remote(op, yielded, parsed);
            }
            returned = System.nanoTime();
            return reply;
        } catch (Exception failure) {
            Log.w("pimalaya", "offline driver failed", failure);
            returned = System.nanoTime();
            return error(failure);
        }
    }

    /** Any other yield; a stager answers "Unsupported engine yield". */
    protected String remote(String op, JSONObject yielded, long parsed) throws JSONException {
        return error("Unsupported engine yield " + op);
    }

    /**
     * What a batch of storage writes did, one entry per placement touched.
     *
     * <p>Ignored here: only a driver reporting to a user counts them.
     */
    protected void applied(JSONArray effects) throws JSONException {}

    /**
     * Stages a flag set on one placement.
     *
     * <p>The next sync pushes its difference from the set the source agreed.
     */
    public void mutateFlags(String collection, String handle, Flags flags) throws JSONException {
        mutate(collection, Mutation.setFlags(handle, flags));
    }

    /**
     * Stages a removal: a tombstone the next sync pushes.
     *
     * <p>A create never pushed is withdrawn instead.
     */
    public void mutateRemove(String collection, String handle) throws JSONException {
        mutate(collection, Mutation.remove(handle));
    }

    /**
     * Stages a move into {@code target}, carried out as a server move.
     *
     * <p>A pending create there and a tombstone here, visible at once (SYNC
     * §3); the next sync of either collection carries it.
     */
    public void mutateMove(String collection, String handle, String target) throws JSONException {
        mutate(collection, Mutation.move(handle, target));
        staged(target);
    }

    /** Stages a content edit; editing a conflicted placement resolves it. */
    public void mutateEdit(String collection, String handle, String body, JSONObject summary,
            String sortKey) throws JSONException {
        mutate(collection, Mutation.edit(handle, PimdirHash.of(body), body, summary, sortKey));
    }

    /** Stages a local item the remote never saw; the next sync appends it. */
    public void mutateAdd(String collection, String linkId, String body, Flags flags,
            JSONObject summary, String sortKey) throws JSONException {
        mutate(
                collection,
                Mutation.add(linkId, flags, PimdirHash.of(body), body, summary, sortKey));
    }

    /**
     * Stages a local item whose body may not be UTF-8 text.
     *
     * <p>As {@link #mutateAdd(String, String, String, Flags, JSONObject,
     * String)}; a message's 8-bit parts cross in base64, stored as the bytes
     * they are.
     */
    public void mutateAdd(String collection, String linkId, byte[] body, Flags flags,
            JSONObject summary, String sortKey) throws JSONException {
        mutate(
                collection,
                Mutation.add(linkId, flags, PimdirHash.of(body), body, summary, sortKey));
    }

    /** Stages one mutation through the engine, then tells the driver. */
    private void mutate(String collection, Mutation mutation) {
        clients.offline().mutate(this, collection, mutation);
        staged(collection);
    }

    /** A mutation was staged there, for a driver that follows writes. */
    protected void staged(String collection) {}

    /** A driver error reply carrying {@code message}. */
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
     * A failure as the driver error reply, keeping a bridge HTTP status.
     *
     * <p>The status survives the engine round trip: the sync entry points
     * branch on it for the token refresh.
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

    /** The HTTP status a bridge failure carries, or null. */
    protected static Integer status(Exception failure) {
        return failure instanceof PimalayaException
                ? ((PimalayaException) failure).status
                : null;
    }
}
