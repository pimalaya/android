package org.pimalaya.calendar;

import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.pimalaya.account.PimDomain;
import org.pimalaya.client.Account;
import org.pimalaya.client.Event;
import org.pimalaya.client.EventDelta;
import org.pimalaya.client.EventRef;
import org.pimalaya.client.PimalayaClient;
import org.pimalaya.client.Transport;
import org.pimalaya.pimdir.PimdirAccount;
import org.pimalaya.pimdir.PimdirDb;
import org.pimalaya.pimdir.PimdirHash;
import org.pimalaya.pimdir.PimdirStorage;
import org.pimalaya.pimdir.PimdirSummary;
import org.pimalaya.pimdir.PimdirEngine;

import java.util.List;
import java.util.concurrent.locks.ReentrantLock;

/**
 * The calendar half of the engine: one driver per account, servicing the
 * remote yields of every calendar it holds, against its server; a
 * calendar's phone collection is its phone pass's ({@link CalendarPhonePass}),
 * against its rows in the phone's calendar provider.
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
public final class CalendarEngine extends PimdirEngine {
    private final Account account;

    /** The pass's sockets; null on a driver that only stages. */
    private final Transport transport;

    /** What the account's collection ids are namespaced under. */
    private final String accountId;

    /**
     * The account's address, the owner the phone gives its calendars: what a
     * write tells a meeting the user organizes by. Null on a driver that only
     * stages.
     */
    private final String address;

    /** The phone pass: the calendar's rows in CalendarContract. */
    private final CalendarPhonePass phone;

    /** The store, for the phone queue its writes reach. */
    private final PimdirDb pimdir;

    public CalendarEngine(
            PimdirDb pimdir,
            PimalayaClient client,
            Transport transport,
            Account account,
            String accountId,
            String address) {
        super(pimdir, client);
        this.transport = transport;
        this.pimdir = pimdir;
        this.account = account;
        this.accountId = accountId;
        this.address = address;
        this.phone = new CalendarPhonePass(pimdir, client);
    }

    /**
     * Reconciles one calendar, three passes: the phone's edits pulled into
     * the store, the exchange with the server (what the phone changed going
     * out with it), then what the server brought projected onto the phone.
     * Between them, the conflicts the merge can settle are settled on both
     * sources ({@link #triage}), which a second exchange pushes.
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
    public void sync(String collection) {
        ReentrantLock lock = lock(collection);
        lock.lock();
        try {
            phoneSide(collection);
            step(Progress.STAGE_SERVER, 0);
            Log.d(
                    "pimalaya",
                    "calendar sync " + collection + ": "
                            + client.offlineSync(this, collection, false));
            hydrate(collection);

            if (triage(collection) > 0) {
                client.offlineSync(this, collection, false);
                hydrate(collection);
            }
            phoneSide(collection);
        } finally {
            lock.unlock();
        }
    }

    /**
     * A phone pass that a failing provider fails alone, never the sync
     * around it, told through this pass's progress.
     */
    private void phoneSide(String collection) {
        phone.progress = progress;
        phone.side(collection);
    }

    /**
     * Settles what the merge can of one source's conflicted entries, and
     * leaves the rest for the entry page (spec/conflicts.md).
     *
     * <p>The triage step of a calendar pass, on any source: the calendar's
     * own id for its server, its phone source's for the phone's. Each
     * conflicted entry is merged three ways from the store alone, its base,
     * the body staged here and the one the source holds, and a merge with
     * nothing to ask is staged on the binding that conflicted, for the
     * caller's next exchange to push. A collision stays marked, for the
     * page. Answers how many it settled.
     */
    int triage(String collection) {
        return CalendarPhonePass.triage(
                this, client, count -> step(Progress.STAGE_RESOLVE, count), collection);
    }

    /** The calendar's process-wide sync lock, keyed by its collection id. */
    private ReentrantLock lock(String collection) {
        return locks.collection(PimdirSummary.CALENDAR, collection);
    }

    @Override
    protected void staged(String collection) {
        CalendarStager.staged(pimdir, collection);
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
        serverOnly(collection);
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
        serverOnly(collection);
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
        return keepBoundNames(collection, reply);
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
        serverOnly(collection);
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
                // own. The handle is whatever came back: the name a CalDAV
                // server filed it by, which Google chooses itself, or the id
                // Graph, Google or JMAP minted.
                String name = PimdirStorage.nameOf(handle);
                try {
                    EventRef created =
                            client.createEvent(
                                    transport,
                                    account,
                                    url,
                                    name,
                                    row.getString("vcard"),
                                    address);
                    return result(
                            handle, true, created.id, landed(url, created.id, created.etag));
                } catch (RuntimeException failure) {
                    // The resource is already there, which `If-None-Match: *`
                    // is exactly what asks: the create is refused rather than
                    // overwriting whatever holds that name.
                    if (isPreconditionFailure(failure)) {
                        return result(handle, false, null, null);
                    }
                    if (isRefusal(failure)) {
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
                                    transport,
                                    account,
                                    url,
                                    handle,
                                    row.getString("vcard"),
                                    ifMatch,
                                    address);
                    return result(handle, true, null, landed(url, handle, etag));
                } catch (RuntimeException failure) {
                    if (isPreconditionFailure(failure)) {
                        return result(handle, false, null, null);
                    }
                    // A 422 is a part of the edit the server refuses for
                    // good, whatever else of it landed: rejected.
                    if (isRefusal(failure)) {
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
                    return result(handle, false, null, null);
                }
                try {
                    client.deleteEvent(transport, account, url, handle, ifMatch, address);
                } catch (RuntimeException failure) {
                    if (isGone(failure)) {
                        // Already gone upstream: the removal converged.
                    } else if (isPreconditionFailure(failure)) {
                        return result(handle, false, null, null);
                    } else if (isRefusal(failure)) {
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

    /**
     * The revision a write landed at: the ETag the server answered, else the
     * one it holds the entry at now, read back.
     *
     * <p>A server storing something other than what it was sent answers no
     * ETag (RFC 4791 section 5.3.4), and Google's CalDAV answers none to any
     * PUT. A base kept with no revision reads the next listing's as a change
     * made elsewhere, which undoes a removal staged before it: the entry is
     * pulled back rather than deleted. A read that fails leaves the revision
     * unknown, as before, rather than failing a write that landed.
     */
    private String landed(String url, String id, String answered) {
        if (answered != null) {
            return answered;
        }
        try {
            List<Event> read = client.multigetEvents(transport, account, url, List.of(id));
            return read.isEmpty() ? null : read.get(0).etag;
        } catch (RuntimeException failure) {
            Log.w("pimalaya", "revision of " + id + " not read back", failure);
            return null;
        }
    }

    /**
     * Whether the server, or the bridge for an object no write of the
     * backend can carry, refused the change for good: a 422.
     */
    private static boolean isRefusal(Exception failure) {
        return Integer.valueOf(422).equals(status(failure));
    }
}
