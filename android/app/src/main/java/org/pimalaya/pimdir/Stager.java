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

/** The storage half of a driver: load/lookup/write yields and staged mutations. */
public abstract class Stager implements OfflineDriver {
    /** The pimdir store's engine seam (placements, writes, conflicts). */
    public final PimdirStorage offline;

    protected final Clients clients;

    /** The store's locks ({@link PimdirDb#locks}): its one writer guards every storage yield. */
    public final Locks locks;

    protected Stager(PimdirDb pimdir, Clients clients) {
        this.offline = new PimdirStorage(pimdir);
        this.clients = clients;
        this.locks = pimdir.locks();
    }

    /**
     * The bodies the store holds under some link ids, as the reply to the
     * lookup an upgrade asks before fetching ({@link
     * PimdirStorage#lookupObjects}): a driver whose link ids name nothing
     * outside its own collection answers fewer.
     */
    protected JSONObject lookup(JSONArray links) throws JSONException {
        return offline.lookupObjects(links);
    }

    /**
     * Where a pass's time goes: the debug log's numbers, page by page, and
     * what a test reads to tell the bridge's share from the store's.
     */
    public static final class Clock {
        /** Nanoseconds in the remote's own call: the network and the connector. */
        long remote;

        /** Nanoseconds turning yields and replies into and out of JSON on this side. */
        long json;

        /** Nanoseconds in the engine between a reply and its next yield, its parse included. */
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

    /** The page under way, logged and folded into {@link #clock} at its write. */
    Clock page = new Clock();

    /** When the last reply went back to the engine; 0 before the first. */
    private long returned;

    /**
     * Counts a remote call toward the page under way, for a driver timing
     * its network apart from reading what came back.
     */
    protected void remote(long nanos) {
        page.remote += nanos;
    }

    /** Nanoseconds this driver has spent on the network so far, the page under way included. */
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

    /** Any yield but load/lookup/write; a stager answers "Unsupported engine yield". */
    protected String remote(String op, JSONObject yielded, long parsed) throws JSONException {
        return error("Unsupported engine yield " + op);
    }

    /**
     * What a batch of storage writes turned out to be, one entry per
     * placement it touched. Ignored here: only a driver reporting a sync
     * to a user has anything to count.
     */
    protected void applied(JSONArray effects) throws JSONException {}

    // ---- staged mutations -------------------------------------------------

    /**
     * Stages a flag set on one placement; the next sync pushes the
     * difference between it and the set the source last agreed on.
     */
    public void mutateFlags(String collection, String handle, Flags flags) throws JSONException {
        mutate(collection, Mutation.setFlags(handle, flags));
    }

    /**
     * Stages a removal: the placement becomes a tombstone the next sync
     * pushes, and a create that was never pushed is withdrawn instead.
     */
    public void mutateRemove(String collection, String handle) throws JSONException {
        mutate(collection, Mutation.remove(handle));
    }

    /**
     * Stages a move into {@code target}: a pending create there and a
     * tombstone here, both visible at once (SYNC §3). The next sync of
     * either collection carries it out as a server move.
     */
    public void mutateMove(String collection, String handle, String target) throws JSONException {
        mutate(collection, Mutation.move(handle, target));
        staged(target);
    }

    /**
     * Stages a copy into {@code target}, the placement kept: a pending
     * create there the next sync pushes as a server-side copy.
     */
    public void mutateCopy(String collection, String handle, String target) throws JSONException {
        mutate(collection, Mutation.copy(handle, target));
        staged(target);
    }

    /**
     * Stages a content edit on one placement; editing a conflicted
     * placement resolves it.
     */
    public void mutateEdit(String collection, String handle, String body, JSONObject summary,
            String sortKey) throws JSONException {
        mutate(collection, Mutation.edit(handle, PimdirHash.of(body), body, summary, sortKey));
    }

    /**
     * Stages a locally authored item the remote has never seen; the next
     * sync pushes it as an append.
     */
    public void mutateAdd(String collection, String linkId, String body, Flags flags,
            JSONObject summary, String sortKey) throws JSONException {
        mutate(
                collection,
                Mutation.add(linkId, flags, PimdirHash.of(body), body, summary, sortKey));
    }

    /**
     * {@link #mutateAdd(String, String, String, Flags, JSONObject, String)}
     * for a body that may not be UTF-8 text (a message's 8-bit parts), which
     * crosses in base64 so it is stored as the bytes it is.
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

    /** A mutation was staged on the collection, for a driver that follows writes. */
    protected void staged(String collection) {}

    // ---- the wire shapes --------------------------------------------------

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
}
