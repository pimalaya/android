package org.pimalaya;

import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.pimalaya.client.Account;
import org.pimalaya.client.Event;
import org.pimalaya.client.EventDelta;
import org.pimalaya.client.EventRef;
import org.pimalaya.client.PimalayaClient;
import org.pimalaya.client.Transport;

import java.util.List;

/**
 * The calendar half of the engine: one driver per account, servicing the
 * remote yields of every calendar it holds.
 *
 * <p>It asks what changed. The enumerate is an RFC 6578 `sync-collection`
 * from the cursor the last pass stored, answering hrefs and ETags, and
 * the fetch is a `calendar-multiget` of the handles the merge asked
 * about. A quiet calendar costs one report; it used to cost every body
 * it holds, because the only listing there was carried them all.
 *
 * <p>The transport it reads and writes on is the caller's, opened once
 * for the pass and closed with it: an account's calendars run side by
 * side ({@link CalendarPool}), each worker on a transport of its own that
 * every round of its calendars shares.
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

    /** The pass's sockets; null on a driver that only stages. */
    private final Transport transport;

    /** What the account's collection ids are namespaced under. */
    private final String accountId;

    CalendarEngine(
            PimdirDb pimdir,
            PimalayaClient client,
            Transport transport,
            Account account,
            String accountId) {
        super(pimdir, client);
        this.transport = transport;
        this.account = account;
        this.accountId = accountId;
    }

    /**
     * Reconciles one calendar with its server: pull what changed, push
     * what is staged, then read back the bodies the pull dropped.
     *
     * <p>What changed and not what there is. The enumerate asks the
     * collection for the members that moved since the cursor the last
     * pass stored, and the fetch reads the bodies of the ones the merge
     * asks about; a quiet calendar costs one report and no body at all,
     * where it used to cost every body it holds.
     *
     * <p>The hydrate is not optional. A sync that finds the remote
     * content changed drops the body on purpose, and the agenda reads its
     * entries by their body: without this pass a remote edit would empty
     * the calendar until something else refetched it, which nothing does.
     */
    void sync(String collection) {
        step(Progress.STAGE_SERVER, 0);
        Log.d(
                "pimalaya",
                "calendar sync " + collection + ": "
                        + client.offlineSync(this, collection, false));
        hydrate(collection);
    }

    /**
     * The calendar's address behind a collection id, which is what every
     * request names it by.
     */
    private String urlOf(String collection) {
        return PimdirAccount.nameOf(accountId, collection);
    }

    @Override
    protected PimDomain domain() {
        return PimDomain.CALENDAR;
    }

    /**
     * The calendar's members, and the bodies of those the round already
     * read whole (Google, JMAP), so naming them reads nothing more.
     */
    @Override
    protected JSONObject enumerate(JSONObject yielded) throws JSONException {
        String collection = yielded.getString("collection");
        String cursor = yielded.isNull("cursor") ? null : yielded.getString("cursor");
        long asked = System.nanoTime();
        EventDelta delta = client.syncEvents(transport, account, urlOf(collection), cursor);
        remote(System.nanoTime() - asked);
        listed(delta.changed.size());

        java.util.Map<String, Event> bodies = new java.util.HashMap<>();
        for (Event event : delta.bodies) {
            bodies.put(event.id, event);
        }

        JSONArray items = new JSONArray();
        for (EventRef event : delta.changed) {
            Event body = bodies.get(event.id);
            JSONObject item = body != null ? entry(body) : new JSONObject();
            item.put("handle", event.id);
            if (event.etag != null) {
                item.put("revision", event.etag);
            }
            items.put(item);
        }

        JSONObject reply = new JSONObject();
        reply.put("items", items);
        reply.put("vanished", new JSONArray(delta.vanished));
        reply.put("complete", delta.complete);
        if (delta.token != null) {
            reply.put("checkpoint", delta.token);
        }
        return reply;
    }

    /**
     * The bodies of the named events, in one `calendar-multiget`.
     *
     * <p>The handles the merge asked about and no others, which is the
     * whole difference between a pass that reads one edited entry and one
     * that reads five hundred to find it.
     */
    @Override
    protected JSONObject fetch(JSONObject yielded) throws JSONException {
        String collection = yielded.getString("collection");
        List<String> handles = stringsOf(yielded.getJSONArray("handles"));

        long asked = System.nanoTime();
        List<Event> read = client.multigetEvents(transport, account, urlOf(collection), handles);
        remote(System.nanoTime() - asked);

        JSONArray items = new JSONArray();
        for (Event event : read) {
            items.put(entry(event));
        }

        JSONObject reply = new JSONObject();
        reply.put("items", items);
        return reply;
    }

    /** One entry at its full tier, as a read or a whole listing hands it over. */
    private static JSONObject entry(Event event) throws JSONException {
        JSONObject item = new JSONObject();
        item.put("handle", event.id);
        // The resource name is the identity: an entry is one object in
        // one calendar, and the UID inside it is what names the series
        // rather than the resource.
        item.put("linkId", event.id);
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
        return item;
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
                // own, which is what a later listing brings back. Graph names
                // its events itself, so the handle is whatever came back.
                String name = PimdirStorage.nameOf(handle);
                try {
                    EventRef created =
                            client.createEvent(
                                    transport, account, url, name, row.getString("vcard"));
                    return result(handle, true, created.id, created.etag);
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
                                    transport, account, url, handle, row.getString("vcard"), ifMatch);
                    return result(handle, true, null, etag);
                } catch (RuntimeException failure) {
                    if (isPreconditionFailure(failure)) {
                        return result(handle, false, null, null);
                    }
                    throw failure;
                }
            }
            case "remove":
                // NOTE: no calendar backend here relocates an entry, and a
                // connector that cannot MUST refuse rather than delete
                // (pimdir SYNC §4): the destination has not received it.
                if (!change.isNull("to") && change.has("to")) {
                    return refuse(collection, change.optString("linkId", null), handle);
                }
                try {
                    client.deleteEvent(transport, account, url, handle, ifMatch);
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
