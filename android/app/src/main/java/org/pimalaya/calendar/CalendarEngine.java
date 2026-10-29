package org.pimalaya.calendar;

import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.pimalaya.account.PimDomain;
import org.pimalaya.client.Account;
import org.pimalaya.client.Clients;
import org.pimalaya.client.Derived;
import org.pimalaya.client.Event;
import org.pimalaya.client.EventDelta;
import org.pimalaya.client.EventRef;
import org.pimalaya.client.EventTarget;
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
 * The calendar half of the engine: one driver per account, against its server.
 *
 * <p>Enumerates by RFC 6578 {@code sync-collection} from the stored cursor,
 * fetches by {@code calendar-multiget}: a quiet calendar costs one report.
 * Phone collections are {@link CalendarPhonePass}'s.
 */
public final class CalendarEngine extends PimdirEngine {
    /**
     * Calendars an account syncs at once.
     *
     * <p>Three workers on every backend: an account rarely has more calendars
     * than that worth waiting on, a CalDAV worker is a connection of its own,
     * and Graph and Google count requests per user.
     */
    public static final int WORKERS = 3;

    private final Account account;

    /** The pass's sockets; null on a driver that only stages. */
    private final Transport transport;

    /** What the account's collection ids are namespaced under. */
    private final String accountId;

    /** The phone pass: the calendar's rows in CalendarContract. */
    private final CalendarPhonePass phone;

    /** The store, for the phone queue its writes reach. */
    private final PimdirDb pimdir;

    /** The driver of one account's calendars, against {@code remote}. */
    public CalendarEngine(PimdirDb pimdir, Clients clients, RemoteAccount remote) {
        super(pimdir, clients);
        this.transport = remote.transport();
        this.pimdir = pimdir;
        this.account = remote.account();
        this.accountId = remote.accountId();
        this.phone = new CalendarPhonePass(pimdir, clients);
    }

    /**
     * Reconciles one calendar: phone edits in, server exchange, projection out.
     *
     * <p>Between them {@link #triage} settles what the merge can on both
     * sources, pushed by a second exchange. The hydrate is not optional: a
     * remote change drops the body, and the agenda reads entries by body.
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
                            + clients.offline().sync(this, collection, false));
            hydrate(collection);

            if (triage(collection) > 0) {
                clients.offline().sync(this, collection, false);
                hydrate(collection);
            }
            phoneSide(collection);
        } finally {
            lock.unlock();
        }
    }

    /**
     * A phone pass a failing provider fails alone, never the sync around it.
     *
     * <p>Told through this pass's progress.
     */
    private void phoneSide(String collection) {
        phone.progress = progress;
        phone.side(collection);
    }

    /**
     * Settles what the merge can of a source's conflicts; answers how many.
     *
     * <p>Each entry merges three ways from the store (spec/conflicts.md): a
     * clean merge is staged on the conflicted binding for the next exchange, a
     * collision stays for the page.
     */
    int triage(String collection) {
        return CalendarPhonePass.triage(
                this, count -> step(Progress.STAGE_RESOLVE, count), collection);
    }

    /** The calendar's process-wide sync lock, keyed by its collection id. */
    private ReentrantLock lock(String collection) {
        return locks.collection(PimdirSummary.CALENDAR, collection);
    }

    @Override
    protected void staged(String collection) {
        CalendarStager.staged(pimdir, collection);
    }

    /** The calendar URL behind a collection id, which every request names. */
    private String urlOf(String collection) {
        return PimdirAccount.nameOf(accountId, collection);
    }

    @Override
    protected PimDomain domain() {
        return PimDomain.CALENDAR;
    }

    /**
     * The calendar's members, and the bodies the round already read whole.
     *
     * <p>Google and JMAP list whole, so naming those reads nothing more.
     */
    @Override
    protected JSONObject enumerate(JSONObject yielded) throws JSONException {
        String collection = yielded.getString("collection");
        serverOnly(collection);
        String cursor = yielded.isNull("cursor") ? null : yielded.getString("cursor");
        long asked = System.nanoTime();
        EventDelta delta = clients.calendar().syncEvents(transport, account, urlOf(collection), cursor);
        remote(System.nanoTime() - asked);
        listed(delta.changed().size());

        java.util.Map<String, Event> bodies = new java.util.HashMap<>();
        for (Event event : delta.bodies()) {
            bodies.put(event.id(), event);
        }

        JSONArray items = new JSONArray();
        for (EventRef event : delta.changed()) {
            Event body = bodies.get(event.id());
            JSONObject item = body != null ? entry(body) : new JSONObject();
            item.put("handle", event.id());
            if (event.etag() != null) {
                item.put("revision", event.etag());
            }
            items.put(item);
        }

        JSONObject reply = new JSONObject();
        reply.put("items", items);
        reply.put("vanished", new JSONArray(delta.vanished()));
        reply.put("complete", delta.complete());
        if (delta.token() != null) {
            reply.put("checkpoint", delta.token());
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
        List<Event> read =
                clients.calendar().multigetEvents(transport, account, urlOf(collection), handles);
        remote(System.nanoTime() - asked);

        JSONArray items = new JSONArray();
        for (Event event : read) {
            items.put(entry(event));
        }

        JSONObject reply = new JSONObject();
        reply.put("items", items);
        return keepBoundNames(collection, reply);
    }

    /** One entry at full tier, as a read or a whole listing hands it over. */
    private static JSONObject entry(Event event) throws JSONException {
        JSONObject item = new JSONObject();
        item.put("handle", event.id());
        // NOTE: the resource name is the identity: an entry is one object in
        // one calendar, and its UID names the series rather than the resource.
        item.put("linkId", event.id());
        item.put("hash", PimdirHash.of(event.ical()));
        item.put("body", event.ical());
        // NOTE: the summary and key io-pimdir derives, which a reader listing
        // items without their bodies reads (the Tasks tab, task_summary). An
        // agenda row still expands at render time against its window.
        Derived derived = CalendarSummaries.of(event.ical());
        item.put("sortKey", derived == null ? "" : derived.sortKey());
        if (derived != null && derived.summary() != null) {
            item.put("summary", derived.summary());
        }
        if (event.etag() != null) {
            item.put("revision", event.etag());
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
     * One change against the server, guarded by what the entry was read at.
     *
     * <p>A refused precondition is reported as a rejection, not raised: the
     * entry moved under the edit and the next pass reconciles. Raising would
     * abort the round, every later change downed by one stale ETag.
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
                // NOTE: the resource name, never the handle: a staged create
                // waits under a provisional handle until this push assigns one
                // (SYNC §2). The handle is what came back: the name a CalDAV
                // server filed it by (Google picks it), or the id Graph,
                // Google or JMAP minted.
                String name = PimdirStorage.nameOf(handle);
                try {
                    EventRef created =
                            clients.calendar().createEvent(
                                    transport,
                                    account,
                                    new EventTarget(url, name, null),
                                    row.getString("vcard"));
                    return result(
                            handle, true, created.id(), landed(url, created.id(), created.etag()));
                } catch (RuntimeException failure) {
                    // NOTE: `If-None-Match: *` refuses the create rather than
                    // overwrite whatever holds that name.
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
                            clients.calendar().updateEvent(
                                    transport,
                                    account,
                                    new EventTarget(url, handle, ifMatch),
                                    row.getString("vcard"));
                    return result(handle, true, null, landed(url, handle, etag));
                } catch (RuntimeException failure) {
                    if (isPreconditionFailure(failure)) {
                        return result(handle, false, null, null);
                    }
                    // NOTE: a 422 refuses part of the edit for good, whatever
                    // else of it landed.
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
                    clients.calendar().deleteEvent(
                            transport, account, new EventTarget(url, handle, ifMatch));
                } catch (RuntimeException failure) {
                    if (isGone(failure)) {
                        // NOTE: already gone upstream: the removal converged.
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
     * The revision a write landed at: the ETag answered, else one read back.
     *
     * <p>A server altering what it stores, and Google's CalDAV always, answer
     * no ETag (RFC 4791 5.3.4): a base with no revision would undo a staged
     * removal. A failed read leaves it unknown.
     */
    private String landed(String url, String id, String answered) {
        if (answered != null) {
            return answered;
        }
        try {
            List<Event> read = clients.calendar().multigetEvents(transport, account, url, List.of(id));
            return read.isEmpty() ? null : read.get(0).etag();
        } catch (RuntimeException failure) {
            Log.w("pimalaya", "revision of " + id + " not read back", failure);
            return null;
        }
    }

    /**
     * Whether the change was refused for good: a 422.
     *
     * <p>By the server, or by the bridge for an object no write of the backend
     * can carry.
     */
    private static boolean isRefusal(Exception failure) {
        return Integer.valueOf(422).equals(status(failure));
    }
}
