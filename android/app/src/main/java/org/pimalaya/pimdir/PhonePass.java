package org.pimalaya.pimdir;

import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.pimalaya.client.Clients;

import java.util.concurrent.locks.ReentrantLock;

/**
 * One collection's phone pass: the store reconciled with its spoke, the
 * server pass's shape. The store reconciled with the provider's rows, the
 * bodies the round brought hydrated, the conflicts the merge can settle
 * settled ({@link #settle}) and pushed in a second exchange. Skipped when
 * the spoke is unavailable, and on the quiet path.
 */
public abstract class PhonePass extends PimdirEngine {
    /** The phone provider this pass reconciles with. */
    private final PhoneSpoke spoke;

    /** The hub's kind ({@link PimdirSummary}), which its collection lock is keyed by. */
    private final String kind;

    /** Whether a phone pass of this driver wrote anything into the store. */
    private boolean ingested;

    protected PhonePass(PimdirDb pimdir, Clients clients, PhoneSpoke spoke, String kind) {
        super(pimdir, clients);
        this.spoke = spoke;
        this.kind = kind;
    }

    /**
     * Runs the pass under the hub's collection lock; answers whether it
     * wrote into the store.
     */
    public boolean sync(String hub) throws JSONException {
        String collection = PimdirStorage.phoneCollection(hub);
        if (!spoke.available(collection)) {
            return ingested;
        }

        ReentrantLock lock = locks.collection(kind, hub);
        lock.lock();
        try {
            // NOTE: quiet path; nothing dirty/staged on either side and
            // matching member counts means the engine pass would reconcile
            // nothing. Neither provider has a per-account changes token, so
            // three cheap counts stand in for one.
            if (!spoke.changed(collection)
                    && !offline.pending(collection)
                    && spoke.count(collection) == offline.memberCount(collection)) {
                return ingested;
            }

            step(Progress.STAGE_PHONE, 0);
            Log.d(
                    "pimalaya",
                    "phone sync "
                            + hub
                            + ": "
                            + clients.offline().sync(this, collection, false));
            hydrate(collection);

            if (settle(collection)) {
                clients.offline().sync(this, collection, false);
                hydrate(collection);
            }
        } finally {
            lock.unlock();
        }
        return ingested;
    }

    /** Whether a pass of this driver wrote anything into the store so far. */
    protected boolean ingested() {
        return ingested;
    }

    /** Settles the spoke's conflicts; answers whether to exchange again. */
    protected abstract boolean settle(String spoke) throws JSONException;

    /** The accepted pushes of one round, for a pass reporting them. */
    protected void pushed(String spoke, JSONArray changes, JSONArray results)
            throws JSONException {}

    /** Not the phone spoke's: projecting onto the device downloads nothing. */
    @Override
    protected final boolean downloads(String collection) {
        return false;
    }

    /** A write to the phone collection is a phone pass's own, pushed by that pass. */
    @Override
    protected final void staged(String collection) {}

    @Override
    protected void applied(JSONArray effects) throws JSONException {
        for (int index = 0; index < effects.length(); index++) {
            ingested |= PimdirStorage.isPhoneCollection(
                    effects.getJSONObject(index).getString("collection"));
        }
    }

    @Override
    protected final JSONObject enumerate(JSONObject yielded) throws JSONException {
        return spoke.enumerate(yielded.getString("collection"));
    }

    @Override
    protected final JSONObject fetch(JSONObject yielded) throws JSONException {
        String collection = yielded.getString("collection");
        return keepBoundNames(
                collection, spoke.fetch(collection, yielded.getJSONArray("handles")));
    }

    @Override
    protected final JSONObject push(JSONObject yielded) throws JSONException {
        String collection = yielded.getString("collection");
        JSONArray changes = yielded.getJSONArray("changes");
        step(Progress.STAGE_PROJECT, changes.length());
        JSONObject reply =
                spoke.push(
                        collection,
                        changes,
                        done -> advance(Progress.STAGE_PROJECT, done, changes.length()));
        pushed(collection, changes, reply.getJSONArray("results"));
        return reply;
    }
}
