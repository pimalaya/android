package org.pimalaya.calendar;

import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.pimalaya.account.PimDomain;
import org.pimalaya.client.Account;
import org.pimalaya.client.CalendarClient;
import org.pimalaya.client.Clients;
import org.pimalaya.client.Derived;
import org.pimalaya.client.Event;
import org.pimalaya.client.EventDelta;
import org.pimalaya.client.EventRef;
import org.pimalaya.client.EventTarget;
import org.pimalaya.client.Transport;
import org.pimalaya.pimdir.PimdirAccount;
import org.pimalaya.pimdir.PimdirDb;
import org.pimalaya.pimdir.PimdirStorage;
import org.pimalaya.pimdir.PimdirSummary;
import org.pimalaya.pimdir.PimdirEngine;

import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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

    /** What a throttled 403 names (rust/src/error.rs, refused_for_good). */
    private static final String[] THROTTLES = {"rate limit", "ratelimit", "quota", "usage limit"};

    private final Account account;

    /** The pass's sockets; null on a driver that only stages. */
    private final Transport transport;

    /** What the account's collection ids are namespaced under. */
    private final String accountId;

    /** The phone pass: the calendar's rows in CalendarContract. */
    private final CalendarPhonePass phone;

    /** The store, for the phone queue its writes reach. */
    private final PimdirDb pimdir;

    /**
     * The phone's zone as a {@code VTIMEZONE}, read once a pass: what Google
     * and Graph read a floating time of a pushed object in.
     */
    private String anchor;

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

    /**
     * The phone's zone as a {@code VTIMEZONE}, from this year's rules on.
     *
     * <p>Google and Graph store no floating time: the bridge reads one in
     * this zone, and a floating {@code UNTIL} as the instant it names here.
     */
    private String anchor() {
        if (anchor == null) {
            anchor = Zones.vtimezone(ZoneId.systemDefault(), System.currentTimeMillis());
        }
        return anchor;
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
        Map<String, String[]> lost = lostCreates(collection, delta.bodies());

        JSONArray items = new JSONArray();
        for (EventRef event : delta.changed()) {
            Event body = bodies.get(event.id());
            String[] create = lost.get(event.id());
            JSONObject item = new JSONObject();
            if (create != null) {
                // NOTE: Google's copy has its own UID: the create's body lands.
                item = entry(new Event(body.id(), body.etag(), create[1]));
                item.put("linkId", create[0]);
            } else if (body != null) {
                item = entry(overlaid(collection, body));
            }
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
            items.put(entry(overlaid(collection, event)));
        }

        JSONObject reply = new JSONObject();
        reply.put("items", items);
        return keepBoundNames(collection, reply);
    }

    /**
     * A Google task as the phone holds it: what Google has nowhere to keep
     * (its state, categories, who it waits on, the mail it came from) laid
     * back from the phone's copy, so a read never takes it away. Any other
     * entry as it came.
     */
    private Event overlaid(String collection, Event event) {
        if (!EventStore.isGoogleTaskList(collection)) {
            return event;
        }
        try {
            JSONObject row = offline.loadRow(collection, event.id());
            if (row == null || row.isNull("vcard")) {
                return event;
            }
            String ical = CalendarClient.overlayTask(event.ical(), row.getString("vcard"));
            return new Event(event.id(), event.etag(), ical);
        } catch (RuntimeException | JSONException failure) {
            Log.w("pimalaya", "task overlay failed: " + event.id(), failure);
            return event;
        }
    }

    /** Each unbound Google task's lost create, as {@code [linkId, body]}. */
    private Map<String, String[]> lostCreates(String collection, List<Event> listed)
            throws JSONException {
        Map<String, String[]> named = new HashMap<>();
        if (!EventStore.isGoogleTaskList(collection) || listed.isEmpty()) {
            return named;
        }
        List<String> handles = new ArrayList<>(listed.size());
        for (Event event : listed) {
            handles.add(event.id());
        }
        Map<String, String> creates;
        Map<String, String[]> bound;
        synchronized (locks.store) {
            creates = offline.pendingCreateBodies(collection);
            if (creates.isEmpty()) {
                return named;
            }
            bound = offline.bound(collection, handles);
        }

        for (Event event : listed) {
            if (bound.containsKey(event.id())) {
                continue;
            }
            Iterator<Map.Entry<String, String>> pending = creates.entrySet().iterator();
            while (pending.hasNext()) {
                Map.Entry<String, String> create = pending.next();
                if (CalendarClient.sameTask(create.getValue(), event.ical())) {
                    named.put(event.id(), new String[] {create.getKey(), create.getValue()});
                    pending.remove();
                    break;
                }
            }
        }
        return named;
    }

    /** One entry at full tier, as a read or a whole listing hands it over. */
    private static JSONObject entry(Event event) throws JSONException {
        JSONObject item = new JSONObject();
        // NOTE: the summary and key io-pimdir derives, which a reader listing
        // items without their bodies reads (the Tasks tab, task_summary). An
        // agenda row still expands at render time against its window.
        Derived derived = CalendarSummaries.of(event.bytes());
        item.put("handle", event.id());
        // NOTE: the key STORAGE §9 assigns: the UID, escaped when spelled like
        // a derived key, else the hash of the bytes. The engine mints a
        // second resource under one UID apart (Annex A.3).
        item.put("linkId", CalendarSummaries.keyOf(derived, event.id()));
        // NOTE: the bytes the server sent, a body that is not UTF-8 included.
        putBody(item, event.bytes(), event.ical(), event.icalBase64());
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
        changes = withoutLossy(collection, changes, results);
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
     * abort the round, every later change downed by one stale ETag. A change
     * the server refuses as it stands is answered refused ({@link #refused}),
     * held by the engine rather than pushed again.
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
                JSONObject supersedes = change.optJSONObject("supersedes");
                if (supersedes != null) {
                    JSONObject moved = moveInto(url, handle, supersedes, row.getString("vcard"));
                    if (moved != null) {
                        return moved;
                    }
                }
                // NOTE: the resource name, never the handle: a staged create
                // waits under a provisional handle until this push assigns one
                // (SYNC §2). The handle is what came back: the name a CalDAV
                // server filed it by (Google picks it), or the id Graph,
                // Google or JMAP minted. An entry is keyed by its UID and filed
                // as UID.ics (a create staged before keyed by that name
                // already), a key the writer derived under a fresh name.
                String name = PimdirStorage.resourceOf(handle);
                if (!name.endsWith(".ics")) {
                    name += ".ics";
                }
                try {
                    EventRef created =
                            clients.calendar().createEvent(
                                    transport,
                                    account,
                                    new EventTarget(url, name, null, anchor()),
                                    row.getString("vcard"));
                    return result(
                            handle, true, created.id(), landed(url, created.id(), created.etag()));
                } catch (RuntimeException failure) {
                    // NOTE: `If-None-Match: *` refuses the create rather than
                    // overwrite whatever holds that name.
                    if (isPreconditionFailure(failure)) {
                        return result(handle, false, null, null);
                    }
                    JSONObject refused = refused(collection, handle, failure);
                    if (refused != null) {
                        return refused;
                    }
                    if (isCreateRefusal(failure)) {
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
                                    new EventTarget(url, handle, ifMatch, anchor()),
                                    row.getString("vcard"));
                    return result(handle, true, null, landed(url, handle, etag));
                } catch (RuntimeException failure) {
                    if (isPreconditionFailure(failure)) {
                        return result(handle, false, null, null);
                    }
                    // NOTE: a 422 refuses part of the edit for good, whatever
                    // else of it landed.
                    JSONObject refused = refused(collection, handle, failure);
                    if (refused != null) {
                        return refused;
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
                        // NOTE: already gone upstream: the removal converged,
                        // a create delivered by a move having taken it along
                        // (pimdir SYNC §4).
                    } else if (isPreconditionFailure(failure)) {
                        // NOTE: an If-Match on a resource gone is false (RFC
                        // 9110 13.1.1), so a strict server (SabreDAV) answers
                        // 412 rather than 404.
                        if (!absent(url, handle)) {
                            return result(handle, false, null, null);
                        }
                    } else {
                        JSONObject refused = refused(collection, handle, failure);
                        if (refused == null) {
                            throw failure;
                        }
                        return refused;
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
     * Delivers a create superseding a member of its identity by moving that
     * member here, then writing the create's body over it (pimdir SYNC §4).
     *
     * <p>The member itself moves, gated on its last-synced ETag, so its {@code
     * UID} never lives in two calendars: Fastmail refuses a create of a {@code
     * UID} another of its calendars holds (403), so filing a task elsewhere by
     * an upload never lands there. Accepted with the moved member's name and
     * the write's revision; the source's tombstone, held until then, then
     * finds it gone. Answers null when the server cannot move it (405, 501,
     * a 404 for the member, as Google answers, a calendar of another account):
     * the caller uploads the body instead. A move refused for the member's
     * identity or its ETag refuses the create, as a refused upload does.
     */
    private JSONObject moveInto(String url, String handle, JSONObject member, String ical)
            throws JSONException {
        String from = member.getString("collection");
        // NOTE: the store's server source is every account's, so the member
        // may be another account's calendar, which no move here reaches.
        if (PimdirAccount.nameOf(accountId, from).equals(from)) {
            return null;
        }
        String ifMatch = member.isNull("ifMatch") ? null : member.getString("ifMatch");
        EventRef moved;
        try {
            moved =
                    clients.calendar().moveEvent(
                            transport,
                            account,
                            new EventTarget(urlOf(from), member.getString("handle"), ifMatch),
                            url);
        } catch (RuntimeException failure) {
            if (isUnmovable(failure)) {
                return null;
            }
            if (isPreconditionFailure(failure) || isRefusal(failure)
                    || isCreateRefusal(failure)) {
                return result(handle, false, null, null);
            }
            throw failure;
        }
        try {
            String etag =
                    clients.calendar().updateEvent(
                            transport,
                            account,
                            new EventTarget(url, moved.id(), moved.etag(), anchor()),
                            ical);
            return result(handle, true, moved.id(), landed(url, moved.id(), etag));
        } catch (RuntimeException failure) {
            // NOTE: the member is here with its old body. Left a pending
            // create, the next listing lands it on that member, whose body the
            // staged one then updates (SYNC §6).
            if (isPreconditionFailure(failure) || isRefusal(failure)
                    || isCreateRefusal(failure)) {
                return result(handle, false, null, null);
            }
            throw failure;
        }
    }

    /**
     * Whether a move was answered as one the server cannot do: 405, 501, or
     * a 404, Google's answer to {@code MOVE} (pimdir SYNC §4).
     */
    private static boolean isUnmovable(Exception failure) {
        Integer status = status(failure);
        return Integer.valueOf(405).equals(status)
                || Integer.valueOf(501).equals(status)
                || Integer.valueOf(404).equals(status);
    }

    /**
     * Whether the server holds no object {@code id} at {@code url}, read
     * back; false when the read fails, the object then not known gone.
     */
    private boolean absent(String url, String id) {
        try {
            return clients.calendar()
                    .multigetEvents(transport, account, url, List.of(id))
                    .isEmpty();
        } catch (RuntimeException failure) {
            Log.w("pimalaya", "whether " + id + " is gone not read back", failure);
            return false;
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

    /**
     * A change the server refused as it stands, answered refused with its
     * reason (pimdir SYNC §4): the engine holds it on its binding and reports
     * it, the calendar's other changes pushed. Null for any other failure,
     * and for an entry gone (404), which is never a refusal.
     *
     * <p>The bridge reads the refusal off the server's answer, a status
     * naming the request, and answers its own refusals for good as a 422.
     */
    private static JSONObject refused(String collection, String handle, Exception failure)
            throws JSONException {
        if (isGone(failure)) {
            return null;
        }
        String reason = refusal(failure);
        if (reason == null && isRefusal(failure)) {
            reason = failure.getMessage();
        }
        if (reason == null) {
            return null;
        }
        Log.w("pimalaya", "change refused: " + collection + " " + handle + ": " + reason);
        return refusedResult(handle, reason);
    }

    /**
     * Whether a create was refused as such: a 403 or a 409.
     *
     * <p>Fastmail answers 403 to a second resource of one {@code UID} in a
     * calendar home, RFC 4791 5.3.2 a 409 to one in the calendar, and a
     * calendar the user may not write a 403: that item's, so it stays a
     * pending create and the pass goes on, as io-pimdir's engine keeps a
     * rejected add (a source it supersedes stays on its server). A 403
     * naming a rate limit or a quota is a throttle, which fails the pass as
     * the bridge's {@code refused_for_good} reads it: a throttled server
     * serves no other write either.
     */
    private static boolean isCreateRefusal(Exception failure) {
        Integer status = status(failure);
        if (Integer.valueOf(409).equals(status)) {
            return true;
        }
        if (!Integer.valueOf(403).equals(status)) {
            return false;
        }
        String message = String.valueOf(failure.getMessage()).toLowerCase(Locale.ROOT);
        for (String throttle : THROTTLES) {
            if (message.contains(throttle)) {
                return false;
            }
        }
        return true;
    }
}
