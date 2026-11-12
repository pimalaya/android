package org.pimalaya.pimdir;

import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.pimalaya.client.Clients;
import org.pimalaya.client.SyncReport;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.locks.ReentrantLock;

/**
 * One collection's phone pass: the store reconciled with its spoke.
 *
 * <p>The server pass's shape: bodies the round brought hydrated, settleable
 * conflicts settled ({@link #settle}) and pushed in a second exchange.
 * Skipped when the spoke is unavailable, and on the quiet path.
 */
public abstract class PhonePass extends PimdirEngine {
    /** The phone provider this pass reconciles with. */
    private final PhoneSpoke spoke;

    /** The hub's kind ({@link PimdirSummary}), keying its collection lock. */
    private final String kind;

    /** Whether a phone pass of this driver wrote anything into the store. */
    private boolean ingested;

    /** The deletions held back from the server, the user to settle. */
    private final HeldDeletions held;

    /** The members the last enumerate let go, their rows erased after it. */
    private final List<String> gone = new ArrayList<>();

    /** A phone pass of {@code kind} (the hub media type) over {@code spoke}. */
    protected PhonePass(PimdirDb pimdir, Clients clients, PhoneSpoke spoke, String kind) {
        super(pimdir, clients);
        this.spoke = spoke;
        this.kind = kind;
        this.held = pimdir.held();
    }

    /** Runs the pass under the hub's collection lock; answers if it wrote. */
    public boolean sync(String hub) throws JSONException {
        String collection = PimdirStorage.phoneCollection(hub);
        if (!spoke.available(collection)) {
            return ingested;
        }

        ReentrantLock lock = locks.collection(kind, hub);
        lock.lock();
        try {
            HeldDeletions.Held restore = held.restoring(collection);
            if (restore != null) {
                // NOTE: unbound before erased, so a crash between loses none.
                offline.forget(collection, restore.handles());
                spoke.purge(collection, restore.handles());
            }

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
            Log.d("pimalaya", "phone sync " + hub + ": " + exchange(collection));
            if (settle(collection)) {
                exchange(collection);
            }
        } finally {
            lock.unlock();
        }
        return ingested;
    }

    /** One engine run over the spoke, then the deleted rows erased. */
    private SyncReport exchange(String collection) throws JSONException {
        gone.clear();
        SyncReport report = clients.offline().sync(this, collection, false);
        hydrate(collection);
        if (!gone.isEmpty()) {
            spoke.purge(collection, new ArrayList<>(gone));
            gone.clear();
        }
        return report;
    }

    /** Whether a pass of this driver wrote anything into the store so far. */
    protected boolean ingested() {
        return ingested;
    }

    /** Settles the spoke's conflicts; answers whether to exchange again. */
    protected abstract boolean settle(String spoke) throws JSONException;

    /** The phone deletions left deleted for a later pass; none by default. */
    protected Set<String> deferred(String spoke, List<String> handles) throws JSONException {
        return Set.of();
    }

    /** The accepted pushes of one round, for a pass reporting them. */
    protected void pushed(String spoke, JSONArray changes, JSONArray results)
            throws JSONException {}

    /** Not the phone spoke's: projecting onto the device downloads nothing. */
    @Override
    protected final boolean downloads(String collection) {
        return false;
    }

    /** A write to the phone collection is this pass's own, pushed by it. */
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
        String collection = yielded.getString("collection");
        JSONObject reply = spoke.enumerate(collection);
        JSONArray vanished = reply.getJSONArray("vanished");
        List<String> handles = new ArrayList<>(vanished.length());
        for (int index = 0; index < vanished.length(); index++) {
            handles.add(vanished.getString(index));
        }
        Set<String> deferred = deferred(collection, handles);
        if (!deferred.isEmpty()) {
            handles.removeAll(deferred);
            reply.put("vanished", new JSONArray(handles));
        }
        int members = offline.memberCount(collection);
        boolean hold =
                HeldDeletions.massive(handles.size(), members) && !held.releases(collection);
        held.passed(collection, hold ? new HeldDeletions.Held(collection, handles, members) : null);
        if (hold) {
            reply.put("vanished", new JSONArray());
        } else {
            gone.addAll(handles);
        }
        return reply;
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
