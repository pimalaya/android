package org.pimalaya.pimdir;

import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.pimalaya.account.PimDomain;
import org.pimalaya.client.Clients;
import org.pimalaya.client.PimalayaException;

import java.util.ArrayList;
import java.util.List;

/**
 * An engine driver's domain-free half: storage yields, staged mutations, wire.
 *
 * <p>Of io-pimdir's six yields, the store's three are one answer for every
 * kind; a domain driver adds the remote's ({@code enumerate}, {@code fetch},
 * {@code push}). Everything blocks: run it off the main thread.
 */
public abstract class PimdirEngine extends Stager {
    /**
     * Observes a pass's coarse steps, on the sync thread; null when headless.
     *
     * <p>The first three stages are every domain's, the last three the phone
     * spokes'. A step carries its engine's domain, which the dock names and
     * weighs its counts by ({@code SyncSteps}).
     */
    public interface Progress {
        /** Exchanging the spine with the server. */
        int STAGE_SERVER = 0;
        /** Downloading {@code count} bodies from the server. */
        int STAGE_DOWNLOAD = 1;
        /** Sending {@code count} changes to the server. */
        int STAGE_UPLOAD = 2;
        /** Reconciling with the phone's contacts or calendar. */
        int STAGE_PHONE = 3;
        /** Writing {@code count} contacts or events to the phone. */
        int STAGE_PROJECT = 4;
        /** Resolving {@code count} conflicts. */
        int STAGE_RESOLVE = 5;

        /** A step began on {@code domain}, {@code count} items when counted. */
        void step(PimDomain domain, int stage, int count);

        /**
         * How far a counted step is: {@code done} of its {@code total} landed.
         *
         * <p>Told only by the steps that move items batch by batch.
         */
        default void advance(PimDomain domain, int stage, int done, int total) {}
    }

    /** The foreground pass's progress observer; null when headless. */
    public Progress progress;

    /** Tells the observer a step started, when there is one. */
    protected void step(int stage, int count) {
        if (progress != null) {
            progress.step(domain(), stage, count);
        }
    }

    /** Tells how far a counted step is, at most once per percent. */
    protected void advance(int stage, int done, int total) {
        if (progress != null && tells(done, total)) {
            progress.advance(domain(), stage, done, total);
        }
    }

    /**
     * Whether {@code done} of {@code total} is worth telling the dock.
     *
     * <p>The last item, or one crossing a percent: a thousand contacts move
     * the bar ten at a time.
     */
    static boolean tells(int done, int total) {
        if (total <= 0 || done <= 0) {
            return false;
        }
        return done >= total || done * 100L / total != (done - 1) * 100L / total;
    }

    /** The domain this driver syncs, which its progress steps are told in. */
    protected abstract PimDomain domain();

    /** A driver over {@code pimdir}, its remotes through {@code clients}. */
    protected PimdirEngine(PimdirDb pimdir, Clients clients) {
        super(pimdir, clients);
    }

    /**
     * Raises every bodiless or stale placement of a collection to full body.
     *
     * <p>A sync seeing remote content change drops the object on purpose, and
     * nothing else refetches it: a domain whose listing reads bodies from the
     * store must run this or a remote edit empties it.
     */
    protected void hydrate(String collection) {
        List<String> pending = offline.handlesBelowFull(collection);
        if (pending.isEmpty()) {
            return;
        }
        if (downloads(collection)) {
            step(Progress.STAGE_DOWNLOAD, pending.size());
        }
        Log.d(
                "pimalaya",
                "hydrate " + collection + " (" + pending.size() + " below full): "
                        + clients.offline().upgrade(this, collection, pending));
    }

    /** Whether reading {@code collection}'s bodies is a download to report. */
    protected boolean downloads(String collection) {
        return true;
    }

    @Override
    protected String remote(String op, JSONObject yielded, long parsed) throws JSONException {
        String reply;
        switch (op) {
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
            case "held":
                reply = held(yielded).toString();
                break;
            default:
                reply = super.remote(op, yielded, parsed);
        }
        return reply;
    }

    /**
     * Which listed handles of a mail step the store holds.
     *
     * <p>The reply to the bridge's {@code held} yield ({@code {held}}); only a
     * mail driver steps.
     */
    protected JSONObject held(JSONObject yielded) throws JSONException {
        throw new UnsupportedOperationException("Only a mail driver steps");
    }

    /** The collection's member spine, as the reply to an enumerate yield. */
    protected abstract JSONObject enumerate(JSONObject yielded) throws JSONException;

    /**
     * Whether this driver's listings already name every member (SYNC §4).
     *
     * <p>A mail listing does, off its headers; a driver answering handles and
     * revisions alone leaves the naming to {@link #named}.
     */
    protected boolean listingsNamed() {
        return false;
    }

    /**
     * Keeps already-bound fetched members under the identity they are filed by.
     *
     * <p>A bound handle naming another identity is the same resource rewritten
     * (Google rewrites UIDs on PUT): read as new, the store would retire the
     * item (STORAGE §10) and lose what was staged on it.
     */
    protected JSONObject keepBoundNames(String collection, JSONObject reply)
            throws JSONException {
        JSONArray items = reply.optJSONArray("items");
        if (items == null || items.length() == 0) {
            return reply;
        }

        List<String> handles = new ArrayList<>(items.length());
        for (int index = 0; index < items.length(); index++) {
            handles.add(items.getJSONObject(index).getString("handle"));
        }
        java.util.Map<String, String[]> bound;
        synchronized (locks.store) {
            bound = offline.bound(collection, handles);
        }

        for (int index = 0; index < items.length(); index++) {
            JSONObject item = items.getJSONObject(index);
            String[] held = bound.get(item.getString("handle"));
            if (held != null && held[0] != null) {
                item.put("linkId", held[0]);
            }
        }
        return reply;
    }

    /** How many bodies one naming read asks for: the DAV multiget batch. */
    private static final int NAMING_BATCH = 64;

    /**
     * Names every member a page lists, so nothing reaches the store unnamed.
     *
     * <p>SYNC §4: a bound member at an unmoved revision by its stored link id;
     * others from their body (carried, else {@link #fetch}ed 64 at a time, via
     * {@link #keepBoundNames}). A member unreadable by now is left out.
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
        java.util.Map<String, String[]> bound;
        synchronized (locks.store) {
            bound = offline.bound(collection, handles);
        }

        List<String> unread = new ArrayList<>();
        for (int index = 0; index < items.length(); index++) {
            JSONObject item = items.getJSONObject(index);
            String[] held = bound.get(item.getString("handle"));
            String revision = item.isNull("revision") ? null : item.optString("revision", null);
            if (held != null && java.util.Objects.equals(held[1], revision)) {
                for (String field : new String[] {"summary", "sortKey", "hash", "body"}) {
                    item.remove(field);
                }
                item.put("linkId", held[0]);
            } else if (!item.has("body")) {
                item.remove("linkId");
                unread.add(item.getString("handle"));
            } else if (held != null && held[0] != null) {
                // NOTE: a bound member keeps its identity, as a fetch's does
                // (keepBoundNames).
                item.put("linkId", held[0]);
            }
        }
        if (unread.isEmpty()) {
            return page;
        }

        boolean told = downloads(collection);
        if (told) {
            step(Progress.STAGE_DOWNLOAD, unread.size());
        }
        java.util.Map<String, JSONObject> bodies = new java.util.HashMap<>();
        for (int from = 0; from < unread.size(); from += NAMING_BATCH) {
            int to = Math.min(unread.size(), from + NAMING_BATCH);
            JSONObject asked = new JSONObject();
            asked.put("collection", collection);
            asked.put("handles", new JSONArray(unread.subList(from, to)));
            asked.put("tier", "full");
            JSONArray fetched = fetch(asked).optJSONArray("items");
            for (int index = 0; fetched != null && index < fetched.length(); index++) {
                JSONObject body = fetched.getJSONObject(index);
                bodies.put(body.getString("handle"), body);
            }
            if (told) {
                advance(Progress.STAGE_DOWNLOAD, to, unread.size());
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

    /** The staged changes carried to the remote, replying to a push yield. */
    protected abstract JSONObject push(JSONObject yielded) throws JSONException;

    /**
     * Refuses a phone collection's yield on a server pass.
     *
     * <p>A collection's phone side is its {@link PhonePass}'s, never the
     * server's.
     */
    protected static void serverOnly(String collection) {
        if (PimdirStorage.isPhoneCollection(collection)) {
            throw new IllegalStateException("Phone collection on a server pass: " + collection);
        }
    }

    /** One push result on the engine wire. */
    public static JSONObject result(
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

    /** Whether a failure is HTTP 412, a precondition the server refused. */
    protected static boolean isPreconditionFailure(Exception failure) {
        return Integer.valueOf(412).equals(status(failure));
    }

    /** Whether a failure is HTTP 404, the resource gone. */
    protected static boolean isGone(Exception failure) {
        return Integer.valueOf(404).equals(status(failure));
    }

    /** True for an HTTP 401 from any backend (expired or revoked token). */
    public static boolean expiredToken(Exception error) {
        return error instanceof PimalayaException
                && Integer.valueOf(401).equals(((PimalayaException) error).status);
    }
}
