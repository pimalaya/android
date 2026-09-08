package org.pimalaya;

import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.pimalaya.client.Account;
import org.pimalaya.client.Event;
import org.pimalaya.client.PimalayaClient;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The calendar half of the engine: one driver per account, servicing the
 * remote yields of every calendar it holds.
 *
 * <p>The simplest of the three drivers, because a calendar listing
 * carries the objects themselves: the enumerate that lists a collection
 * has already read every body, so the fetch beside it is a cache lookup
 * and no round trip. What that buys is the whole point of the engine
 * here, a staged create, edit or delete surviving a refresh instead of
 * being replaced by it.
 *
 * <p>Incremental listing is not wired: CalDAV's ctag and sync-token
 * rounds are what one would use, so every enumerate is a complete round
 * and reports itself as one. The engine reconciles a complete spine
 * exactly as it reconciles a delta; what it costs is one request per
 * calendar per sync, which is what the previous whole-collection refresh
 * cost too.
 */
final class CalendarEngine extends PimdirEngine {
    private final Account account;

    /** What the account's collection ids are namespaced under. */
    private final String accountId;

    /** The bodies the pass's enumerate listed, by collection and handle. */
    private final Map<String, Map<String, Event>> listed = new HashMap<>();

    CalendarEngine(PimdirDb pimdir, PimalayaClient client, Account account, String accountId) {
        super(pimdir, client);
        this.account = account;
        this.accountId = accountId;
    }

    /**
     * Reconciles one calendar with the objects just listed off its server:
     * pull, push what is staged, then put back the bodies the pull dropped.
     *
     * <p>The listing is the caller's, as the account walk is the mail
     * driver's, so a pass makes exactly one request per calendar however
     * many rounds the merge needs.
     *
     * <p>The hydrate is not optional. A sync that finds the remote content
     * changed drops the object and leaves the placement below full, and
     * the agenda reads entries by their body: without this pass a remote
     * edit would empty the calendar until something else refetched it,
     * which nothing does.
     */
    void sync(String collection, List<Event> events) {
        Map<String, Event> bodies = new HashMap<>(events.size());
        for (Event event : events) {
            bodies.put(event.id, event);
        }
        listed.put(collection, bodies);

        try {
            step(Progress.STAGE_SERVER, 0);
            Log.d(
                    "pimalaya",
                    "calendar sync " + collection + ": "
                            + client.offlineSync(this, collection, false));
            hydrate(collection);
        } finally {
            listed.remove(collection);
        }
    }

    /**
     * Announces nothing: a calendar's fetch is a lookup in the listing the
     * pass already read, so there is no download to report.
     */
    @Override
    protected void hydrating(String collection, int count) {}

    /**
     * The calendar's address behind a collection id, which is what every
     * request names it by.
     */
    private String urlOf(String collection) {
        return PimdirAccount.nameOf(accountId, collection);
    }

    @Override
    protected JSONObject enumerate(JSONObject yielded) throws JSONException {
        String collection = yielded.getString("collection");
        Map<String, Event> bodies = listed.get(collection);

        JSONArray items = new JSONArray();
        for (Event event : bodies == null ? List.<Event>of() : bodies.values()) {
            JSONObject item = new JSONObject();
            item.put("handle", event.id);
            if (event.etag != null) {
                item.put("revision", event.etag);
            }
            items.put(item);
        }

        JSONObject reply = new JSONObject();
        reply.put("items", items);
        reply.put("vanished", new JSONArray());
        reply.put("complete", true);
        return reply;
    }

    @Override
    protected JSONObject fetch(JSONObject yielded) throws JSONException {
        String collection = yielded.getString("collection");
        Map<String, Event> bodies = listed.get(collection);

        JSONArray items = new JSONArray();
        for (String handle : stringsOf(yielded.getJSONArray("handles"))) {
            // NOTE: a handle the listing did not carry is one the server
            // dropped between the two, so it is left out rather than read
            // again: the enumerate that follows reports it vanished.
            Event event = bodies == null ? null : bodies.get(handle);
            if (event == null) {
                continue;
            }

            JSONObject item = new JSONObject();
            item.put("handle", handle);
            // The resource name is the identity: an entry is one object in
            // one calendar, and the UID inside it is what names the series
            // rather than the resource.
            item.put("linkId", handle);
            item.put("hash", PimdirHash.of(event.ical));
            item.put("body", event.ical);
            // NOTE: no key and no summary. What an agenda row shows needs the
            // recurrence expansion, which happens at render time against the
            // window being shown, so there is nothing to write here that a
            // listing could read.
            item.put("sortKey", "");
            if (event.etag != null) {
                item.put("revision", event.etag);
            }
            items.put(item);
        }

        JSONObject reply = new JSONObject();
        reply.put("items", items);
        return reply;
    }

    @Override
    protected JSONObject push(JSONObject yielded) throws JSONException {
        String collection = yielded.getString("collection");
        JSONArray changes = yielded.getJSONArray("changes");
        step(Progress.STAGE_UPLOAD, changes.length());

        JSONArray results = new JSONArray();
        for (int index = 0; index < changes.length(); index++) {
            results.put(pushOne(collection, changes.getJSONObject(index)));
        }

        JSONObject reply = new JSONObject();
        reply.put("results", results);
        return reply;
    }

    /**
     * One change against the server, guarded by what the entry was read
     * at.
     *
     * <p>A precondition the server refuses is reported as a rejection
     * rather than raised: the entry moved under the edit, so what the push
     * was conditioned on is no longer true, and the next pass reconciles
     * against what is there now. Raising would abort the round and take
     * every change after it down with one stale ETag.
     */
    private JSONObject pushOne(String collection, JSONObject change) throws JSONException {
        String url = urlOf(collection);
        String handle = change.getString("handle");
        String ifMatch = change.isNull("ifMatch") ? null : change.getString("ifMatch");

        switch (change.getString("op")) {
            case "add": {
                JSONObject row = offline.loadRow(collection, handle);
                if (row == null) {
                    return result(handle, false, null, null);
                }
                // The resource name, never the handle: a staged create waits
                // under a provisional handle until this push assigns a real
                // one (SYNC §2), and the name it goes out under is the entry's
                // own, which is what a later listing brings back.
                String name = PimdirStorage.nameOf(handle);
                try {
                    String etag = client.createEvent(account, url, name, row.getString("vcard"));
                    return result(handle, true, name, etag);
                } catch (RuntimeException failure) {
                    // The resource is already there, which `If-None-Match: *`
                    // is exactly what asks: the create is refused rather than
                    // overwriting whatever holds that name.
                    if (isPreconditionFailure(failure)) {
                        return result(handle, false, null, null);
                    }
                    throw failure;
                }
            }
            case "update": {
                JSONObject row = offline.loadRow(collection, handle);
                if (row == null) {
                    return result(handle, false, null, null);
                }
                try {
                    String etag =
                            client.updateEvent(
                                    account, url, handle, row.getString("vcard"), ifMatch);
                    return result(handle, true, null, etag);
                } catch (RuntimeException failure) {
                    if (isPreconditionFailure(failure)) {
                        return result(handle, false, null, null);
                    }
                    throw failure;
                }
            }
            case "remove":
                try {
                    client.deleteEvent(account, url, handle, ifMatch);
                } catch (RuntimeException failure) {
                    if (isGone(failure)) {
                        // Already gone upstream: the removal converged.
                    } else if (isPreconditionFailure(failure)) {
                        return result(handle, false, null, null);
                    } else {
                        throw failure;
                    }
                }
                return result(handle, true, null, null);
            default:
                // NOTE: no flags on any calendar backend, so a flag change is
                // nothing to carry rather than something to refuse.
                return result(handle, true, null, null);
        }
    }
}
