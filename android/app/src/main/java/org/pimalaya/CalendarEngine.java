package org.pimalaya;

import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.pimalaya.client.Account;
import org.pimalaya.client.Event;
import org.pimalaya.client.EventDelta;
import org.pimalaya.client.EventMerge;
import org.pimalaya.client.EventRef;
import org.pimalaya.client.PimalayaClient;
import org.pimalaya.client.Transport;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * The calendar half of the engine: one driver per account, servicing the
 * remote yields of every calendar it holds, against its server or, for a
 * calendar's phone collection, against its rows in the phone's calendar
 * provider through the {@link CalendarRemote} adapter.
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
    /**
     * One lock per calendar, process-wide, as {@link OfflineEngine} holds one
     * per book: the in-app sync, the background run, Android's upload sync
     * and the pass after a write can each reconcile the same calendar with
     * the phone, and a pass spans many store transactions. Reentrant, so a
     * calendar pass's own phone passes nest under its hold.
     */
    private static final ConcurrentHashMap<String, ReentrantLock> SYNC_LOCKS =
            new ConcurrentHashMap<>();

    private final Account account;

    /** The pass's sockets; null on a driver that only stages. */
    private final Transport transport;

    /** What the account's collection ids are namespaced under. */
    private final String accountId;

    /** The phone spoke's adapter: the calendar's rows in CalendarContract. */
    private final CalendarRemote phone;

    /** Whether a phone pass of this driver wrote anything into the store. */
    private boolean ingested;

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
        this.phone = new CalendarRemote(pimdir.context(), pimdir);
    }

    /**
     * The phone pass of one calendar on its own, offline: its calendar-app
     * edits brought into the store and the store's projected onto it. What
     * the triggers outside a sync run ({@link CalendarRows#phonePass}).
     * Answers whether it wrote anything into the store.
     */
    static boolean phonePass(PimdirDb pimdir, String collection) {
        CalendarEngine engine = new CalendarEngine(pimdir, new PimalayaClient(), null, null, null);
        engine.phoneSide(collection);
        return engine.ingested;
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
    void sync(String collection) {
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

    /** A phone pass that a failing provider fails alone, never the sync around it. */
    private void phoneSide(String collection) {
        try {
            syncPhone(collection);
        } catch (RuntimeException failure) {
            Log.w("pimalaya", "calendar phone pass failed for " + collection, failure);
        }
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
        List<EventStore.StoredConflict> conflicts;
        synchronized (STORE) {
            conflicts = EventStore.conflictsOf(offline, collection);
        }
        if (conflicts.isEmpty()) {
            return 0;
        }

        step(Progress.STAGE_RESOLVE, conflicts.size());
        int settled = 0;
        for (EventStore.StoredConflict conflict : conflicts) {
            try {
                EventMerge merge =
                        client.mergeEvent(conflict.base, conflict.local, conflict.remote);
                if (merge.resolved) {
                    mutateEdit(collection, conflict.handle, merge.ical, null, "");
                    settled += 1;
                }
            } catch (RuntimeException | JSONException failure) {
                // NOTE: one entry the merge cannot read stays conflicted
                // rather than taking the rest of the pass down.
                Log.w("pimalaya", "calendar triage failed: " + conflict.handle, failure);
            }
        }
        return settled;
    }

    /**
     * The calendar's phone pass, the server pass's shape: the store
     * reconciled with the calendar's rows, the bodies the round brought
     * hydrated. Skipped when the phone does not show the calendar or the
     * permission is gone, and on the quiet path: nothing changed on the
     * phone, nothing staged for it, and as many objects on both sides, three
     * cheap checks where CalendarContract has no changes token.
     *
     * <p>Tasks and journal entries take part as objects that show nothing:
     * the provider holds events alone ({@link CalendarRemote}).
     */
    void syncPhone(String collection) {
        String spoke = PimdirStorage.phoneCollection(collection);
        if (!phone.available(spoke)) {
            return;
        }

        ReentrantLock lock = lock(collection);
        lock.lock();
        try {
            if (!phone.changed(spoke)
                    && !offline.pending(spoke)
                    && phone.count(spoke) == offline.memberCount(spoke)) {
                return;
            }

            step(Progress.STAGE_PHONE, 0);
            Log.d(
                    "pimalaya",
                    "calendar phone sync " + collection + ": "
                            + client.offlineSync(this, spoke, false));
            hydrate(spoke);

            // NOTE: a phone binding both sides changed goes through the same
            // triage as the server's: what the merge settles is pushed to
            // the phone at once, a collision is left to the form.
            if (triage(spoke) > 0) {
                client.offlineSync(this, spoke, false);
                hydrate(spoke);
            }
        } finally {
            lock.unlock();
        }
    }

    /** The calendar's process-wide sync lock, keyed by its collection id. */
    private static ReentrantLock lock(String collection) {
        return SYNC_LOCKS.computeIfAbsent(collection, key -> new ReentrantLock());
    }

    /**
     * Announces a hydrate, unless it is the phone spoke's: projecting a
     * calendar onto the device downloads nothing.
     */
    @Override
    protected void hydrating(String collection, int count) {
        if (!PimdirStorage.isPhoneCollection(collection)) {
            step(Progress.STAGE_DOWNLOAD, count);
        }
    }

    /**
     * A write to a calendar reaches the phone's calendar within a second
     * ({@link PhoneQueue}); a write to the phone collection is a phone
     * pass's own, pushed by that pass.
     */
    @Override
    protected void staged(String collection) {
        if (!PimdirStorage.isPhoneCollection(collection)) {
            PhoneQueue.calendarWritten(collection);
        }
    }

    @Override
    protected void applied(JSONArray effects) throws JSONException {
        for (int index = 0; index < effects.length(); index++) {
            ingested |= PimdirStorage.isPhoneCollection(
                    effects.getJSONObject(index).getString("collection"));
        }
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
        if (PimdirStorage.isPhoneCollection(collection)) {
            return phone.enumerate(collection);
        }
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
        if (PimdirStorage.isPhoneCollection(collection)) {
            return phone.fetch(collection, yielded.getJSONArray("handles"));
        }
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
        if (PimdirStorage.isPhoneCollection(collection)) {
            step(Progress.STAGE_PROJECT, changes.length());
            return phone.push(collection, changes);
        }
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
                    // A 422 is a part of the edit the server refuses for
                    // good, whatever else of it landed: rejected, and the
                    // row says so rather than waiting.
                    if (Integer.valueOf(422).equals(status(failure))) {
                        String linkId;
                        synchronized (STORE) {
                            linkId = offline.linkOfHandle(collection, handle);
                        }
                        return refuse(collection, linkId, handle);
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
