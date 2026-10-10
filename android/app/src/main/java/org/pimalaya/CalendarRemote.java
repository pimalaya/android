package org.pimalaya;

import android.accounts.Account;
import android.content.ContentProviderOperation;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.content.OperationApplicationException;
import android.database.Cursor;
import android.database.SQLException;
import android.net.Uri;
import android.os.RemoteException;
import android.os.TransactionTooLargeException;
import android.provider.CalendarContract;
import android.provider.CalendarContract.Attendees;
import android.provider.CalendarContract.Calendars;
import android.provider.CalendarContract.Events;
import android.provider.CalendarContract.ExtendedProperties;
import android.provider.CalendarContract.Reminders;
import android.util.Log;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.function.IntConsumer;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.pimalaya.CalendarMapping.Row;
import org.pimalaya.CalendarMapping.Write;
import org.pimalaya.client.EventTime;
import org.pimalaya.client.EventViews;
import org.pimalaya.client.PimalayaException;

/**
 * The CalendarContract half of the calendar mirror: the phone spoke's remote
 * adapter, serving the engine's enumerate, fetch and push yields against one
 * mirrored calendar's rows (docs/calendar-mapping.md, change detection and
 * writes). One object is one master row (its handle in {@code _SYNC_ID}) and
 * a row per override, with their reminders, attendees and URL property.
 *
 * <p>Every write carries {@code CALLER_IS_SYNCADAPTER} and the account, so
 * nothing loops back as an edit; {@code DIRTY}, which only calendar apps
 * set, is the change signal, and the revision is the token stamped at
 * convergence ({@code SYNC_DATA1}) while an object's rows are clean. Pushes
 * are guarded on the rows not being dirty, the stamp in the same batch, so
 * an edit racing the pass stays for the next. The mapping itself is
 * {@link CalendarMapping}, pure and JVM-tested; this class owns what needs a
 * device.
 */
final class CalendarRemote {
    /** What an object's revision starts with while a calendar app's edit waits in its rows. */
    private static final String DIRTY = "dirty:";

    /** Selects a row no calendar app changed since it was last written. */
    private static final String CLEAN =
            "(" + Events.DIRTY + " IS NULL OR " + Events.DIRTY + " = 0)";

    /** How many writes a part of an object too large for one transaction carries. */
    private static final int PART = 100;

    /** The content columns of an event row, what the mapping reads. */
    private static final String[] COLUMNS = {
        Events._SYNC_ID,
        Events.UID_2445,
        Events.TITLE,
        Events.DESCRIPTION,
        Events.EVENT_LOCATION,
        Events.DTSTART,
        Events.DTEND,
        Events.EVENT_TIMEZONE,
        Events.EVENT_END_TIMEZONE,
        Events.DURATION,
        Events.ALL_DAY,
        Events.RRULE,
        Events.RDATE,
        Events.EXDATE,
        Events.EXRULE,
        Events.ORIGINAL_ID,
        Events.ORIGINAL_SYNC_ID,
        Events.ORIGINAL_INSTANCE_TIME,
        Events.ORIGINAL_ALL_DAY,
        Events.STATUS,
        Events.AVAILABILITY,
        Events.ACCESS_LEVEL,
        Events.EVENT_COLOR_KEY,
        Events.ORGANIZER,
        Events.IS_ORGANIZER,
        Events.HAS_ATTENDEE_DATA,
    };

    /** The sync state of an event row, what a listing reads. */
    private static final String[] STATE = {
        Events._ID,
        Events._SYNC_ID,
        Events.ORIGINAL_ID,
        Events.ORIGINAL_SYNC_ID,
        Events.ORIGINAL_INSTANCE_TIME,
        Events.DIRTY,
        Events.DELETED,
        Events.SYNC_DATA1,
        Events.SYNC_DATA2,
        Events.SYNC_DATA3,
        Events.SYNC_DATA4,
        Events.UID_2445,
    };

    private final Context context;

    /** The store's engine seam, for the bodies the phone last converged on. */
    private final PimdirStorage offline;

    CalendarRemote(Context context, PimdirDb pimdir) {
        this.context = context;
        this.offline = new PimdirStorage(pimdir);
    }

    /** One mirrored calendar: its account and its {@code Calendars} row. */
    static final class Target {
        final Account account;
        final long id;
        final boolean writable;

        /** Its {@code OWNER_ACCOUNT}, the user's address. */
        final String owner;

        /** The objects bound to it that show nothing ({@code CAL_SYNC3}). */
        final int hidden;

        Target(Account account, long id, boolean writable, String owner, int hidden) {
            this.account = account;
            this.id = id;
            this.writable = writable;
            this.owner = owner;
            this.hidden = hidden;
        }
    }

    /**
     * The calendar a phone collection stands for, null when the phone does
     * not show it (no {@code Calendars} row, {@link CalendarRows#reconcile}).
     */
    Target target(String collection) {
        try (Cursor cursor =
                context.getContentResolver()
                        .query(
                                Calendars.CONTENT_URI,
                                new String[] {
                                    Calendars._ID,
                                    Calendars.ACCOUNT_NAME,
                                    Calendars.CALENDAR_ACCESS_LEVEL,
                                    Calendars.OWNER_ACCOUNT,
                                    Calendars.CAL_SYNC3
                                },
                                Calendars.ACCOUNT_TYPE + " = ? AND " + Calendars._SYNC_ID + " = ?",
                                new String[] {
                                    Accounts.TYPE, PimdirStorage.collectionOf(collection)
                                },
                                null)) {
            if (cursor == null || !cursor.moveToFirst()) {
                return null;
            }
            String hidden = cursor.isNull(4) ? "0" : cursor.getString(4);
            return new Target(
                    new Account(cursor.getString(1), Accounts.TYPE),
                    cursor.getLong(0),
                    cursor.getInt(2) >= Calendars.CAL_ACCESS_CONTRIBUTOR,
                    cursor.isNull(3) ? cursor.getString(1) : cursor.getString(3),
                    parse(hidden));
        }
    }

    /** Whether the spoke can serve the collection: the permission granted, the calendar shown. */
    boolean available(String collection) {
        return PhoneMirror.CALENDAR.granted(context) && target(collection) != null;
    }

    /**
     * Whether the calendar's rows carry anything for the store, or owe a
     * projection: a calendar app's edit, deletion or creation, an exception
     * row gone below the count stamped on its master, or a row projected
     * for another device zone or another window. One provider query, the
     * phone's share of the quiet path.
     */
    boolean changed(String collection) {
        Target target = requireTarget(collection);
        List<State> states = states(target, null);
        Map<String, Integer> exceptions = new HashMap<>();
        for (State state : states) {
            if (state.dirty || state.deleted || (state.top() && state.syncId == null)) {
                return true;
            }
            if (!state.top() && state.originalSyncId != null) {
                exceptions.merge(state.originalSyncId, 1, Integer::sum);
            }
        }
        for (State state : states) {
            if (state.top() && state.syncId != null
                    && (exceptions.getOrDefault(state.syncId, 0) < state.count
                            || stale(state.projected))) {
                return true;
            }
        }
        return false;
    }

    /**
     * The objects the calendar holds as the store counts them: those with
     * rows, and those bound to it that show nothing (tasks, refused writes).
     */
    int count(String collection) {
        Target target = requireTarget(collection);
        Set<String> items = new HashSet<>();
        for (State state : states(target, null)) {
            if (state.top() && state.syncId != null && !state.deleted) {
                items.add(CalendarMapping.itemOf(state.syncId));
            }
        }
        return items.size() + target.hidden;
    }

    /**
     * Services an enumerate yield: the calendar's objects, each as its handle
     * and its revision. A delta round, never complete: only rows a calendar
     * app deleted vanish, so a calendar the provider emptied never reads as a
     * mass deletion. Before listing, created masters and the clones the
     * provider's own split leaves are stamped a fresh handle, an override
     * linked by row alone gets its master's, rows of objects the store no
     * longer holds are purged, and clean rows projected for another zone or
     * window are projected again. A read-only calendar takes no phone change:
     * each is put back as the store has it.
     */
    JSONObject enumerate(String collection) throws JSONException {
        Target target = requireTarget(collection);
        ContentResolver resolver = context.getContentResolver();

        List<State> states = states(target, null);
        Map<Long, State> byId = new HashMap<>();
        Map<String, List<State>> tops = new LinkedHashMap<>();
        for (State state : states) {
            byId.put(state.id, state);
        }

        // NOTE: the provider's split clones the master's handle and sync
        // columns into the new series; the first row keeps the handle.
        Set<String> seen = new HashSet<>();
        for (State state : states) {
            if (!state.top() || state.deleted) {
                continue;
            }
            boolean clone =
                    state.syncId != null
                            && ((state.self != null && state.self != state.id)
                                    || (state.syncId.indexOf(CalendarMapping.STANDALONE) < 0
                                            && !seen.add(state.syncId)));
            if (state.syncId == null || clone) {
                String handle = UUID.randomUUID() + ".ics";
                String uid = clone || state.uid == null || state.uid.isEmpty()
                        ? UUID.randomUUID().toString()
                        : state.uid;
                ContentValues values = new ContentValues();
                values.put(Events._SYNC_ID, handle);
                values.put(Events.UID_2445, uid);
                values.putNull(Events.SYNC_DATA1);
                values.putNull(Events.SYNC_DATA2);
                values.put(Events.SYNC_DATA3, state.id);
                resolver.update(eventUri(target, state.id), values, null, null);
                state.syncId = handle;
                state.token = null;
                state.count = 0;
                state.created = true;
            }
        }

        // NOTE: an app linking an override by row alone (Fossify) leaves the
        // provider unable to pair it with its instance.
        for (State state : states) {
            if (state.top() || state.originalSyncId != null || state.originalId == null) {
                continue;
            }
            State master = byId.get(state.originalId);
            if (master != null && master.syncId != null) {
                ContentValues values = new ContentValues();
                values.put(Events.ORIGINAL_SYNC_ID, master.syncId);
                resolver.update(eventUri(target, state.id), values, null, null);
                state.originalSyncId = master.syncId;
            }
        }

        for (State state : states) {
            if (state.top() && state.syncId != null) {
                tops.computeIfAbsent(CalendarMapping.itemOf(state.syncId), key -> new ArrayList<>())
                        .add(state);
            }
        }

        JSONArray items = new JSONArray();
        JSONArray vanished = new JSONArray();
        for (Map.Entry<String, List<State>> entry : tops.entrySet()) {
            String handle = entry.getKey();
            List<State> rows = entry.getValue();
            List<State> exceptions = new ArrayList<>();
            for (State state : states) {
                if (!state.top() && handle.equals(state.originalSyncId)) {
                    exceptions.add(state);
                }
            }

            boolean deleted = true;
            boolean created = false;
            boolean dirty = false;
            for (State state : rows) {
                deleted &= state.deleted;
                created |= state.created;
                dirty |= state.dirty || state.deleted || state.token == null;
            }
            int live = 0;
            for (State state : exceptions) {
                dirty |= state.dirty || state.deleted;
                live += state.deleted ? 0 : 1;
            }
            dirty |= live < rows.get(0).count;

            if (!target.writable) {
                if (!dirty && offline.loadRow(collection, handle) != null) {
                    items.put(listed(handle, rows.get(0).token));
                    continue;
                }
                String base = created ? "" : phoneBase(collection, handle);
                if (base.isEmpty()) {
                    purge(target, rows, exceptions);
                    continue;
                }
                write(target, handle, base, rows(target, handle, true));
                items.put(listed(handle, PimdirHash.of(base)));
                continue;
            }

            if (deleted) {
                // NOTE: a sync-adapter delete removes that row alone, so the
                // purge names the master's exception rows too.
                vanished.put(handle);
                purge(target, rows, exceptions);
                continue;
            }
            if (!dirty && !created && offline.loadRow(collection, handle) == null) {
                // NOTE: a projection of an object the store dropped
                // (interrupted delete, store rebuilt).
                purge(target, rows, exceptions);
                continue;
            }

            String revision = rows.get(0).token;
            if (dirty) {
                revision = DIRTY + CalendarMapping.fingerprint(rows(target, handle, false));
            } else if (stale(rows.get(0).projected)) {
                write(target, handle, phoneBase(collection, handle), null);
            }
            items.put(listed(handle, revision));
        }

        hide(target, collection, tops.keySet());

        JSONObject reply = new JSONObject();
        reply.put("items", items);
        reply.put("vanished", vanished);
        reply.put("complete", false);
        return reply;
    }

    private static JSONObject listed(String handle, String revision) throws JSONException {
        JSONObject item = new JSONObject();
        item.put("handle", handle);
        item.put("revision", revision);
        return item;
    }

    /**
     * Counts the objects bound to the calendar that show nothing on it, for
     * the quiet path's member count.
     */
    private void hide(Target target, String collection, Set<String> shown) throws JSONException {
        JSONArray placements = offline.loadCollection(collection, null).optJSONArray("placements");
        int hidden = 0;
        for (int index = 0; placements != null && index < placements.length(); index++) {
            JSONObject placement = placements.getJSONObject(index);
            if (placement.has("base")
                    && !"tombstone".equals(placement.optString("status"))
                    && !shown.contains(placement.getString("handle"))) {
                hidden++;
            }
        }
        if (hidden != target.hidden) {
            ContentValues values = new ContentValues();
            values.put(Calendars.CAL_SYNC3, String.valueOf(hidden));
            context.getContentResolver()
                    .update(
                            CalendarRows.asSyncAdapter(
                                    ContentUris.withAppendedId(Calendars.CONTENT_URI, target.id),
                                    target.account),
                            values,
                            null,
                            null);
        }
    }

    /**
     * Services a fetch yield: each handle's rows read back as the object.
     * The phone carries only what the mapping does, so the body is never
     * rebuilt from rows: the rows are merged over what the object the phone
     * last converged on projects (field space, {@link CalendarMapping#merge})
     * and that is patched onto it, so whatever the phone cannot hold
     * survives. An untouched object rides back byte for byte, and every read
     * stamps the rows converged, so a fetch is content-quiet on both sides.
     */
    JSONObject fetch(String collection, JSONArray handles) throws JSONException {
        JSONArray items = new JSONArray();
        for (int index = 0; index < handles.length(); index++) {
            JSONObject item = read(collection, handles.getString(index));
            if (item != null) {
                items.put(item);
            }
        }
        JSONObject reply = new JSONObject();
        reply.put("items", items);
        return reply;
    }

    /** One handle's fetched object; null once its rows are gone. */
    JSONObject read(String collection, String handle) throws JSONException {
        Target target = requireTarget(collection);
        List<Row> observed = rows(target, handle, true);
        List<Row> live = new ArrayList<>();
        for (Row row : observed) {
            if (!row.deleted) {
                live.add(row);
            }
        }
        if (live.isEmpty()) {
            return null;
        }

        String base = phoneBase(collection, handle);
        JSONObject view =
                base.isEmpty() ? new JSONObject() : EventViews.projectEvent(base, now());
        CalendarMapping.Phone phone = phone(target, handle);

        boolean revert = !target.writable || CalendarMapping.reverts(view, live, phone);
        JSONObject merged = target.writable ? CalendarMapping.merge(view, live, phone) : null;
        String body = merged == null ? base : EventViews.applyEvent(base, edit(merged, target));
        if (merged != null) {
            Log.d("pimalaya", "phone edit on " + handle);
        }
        String token = PimdirHash.of(body);

        boolean stamped =
                revert
                        ? write(target, handle, body, observed)
                        : stamp(target, handle, observed, token, projected(body));
        String revision =
                stamped ? token : DIRTY + CalendarMapping.fingerprint(live);

        JSONObject item = new JSONObject();
        item.put("handle", handle);
        item.put("linkId", handle);
        item.put("hash", token);
        item.put("body", body);
        item.put("sortKey", "");
        item.put("revision", revision);
        return item;
    }

    /**
     * The edit patching a merged view onto its object: now as the stamp, the
     * user's address, and the definition of every zone the view names that
     * the platform knows, for a time the phone moved into one.
     */
    private JSONObject edit(JSONObject merged, Target target) throws JSONException {
        Set<String> zones = new TreeSet<>();
        zones(merged, zones);
        long since = System.currentTimeMillis();
        JSONObject master = merged.optJSONObject("master");
        EventTime start =
                master == null ? null : CalendarMapping.time(master.optJSONObject("start"));
        if (start != null && !start.isDate()) {
            since = Math.min(since, Zones.instant(start, ZoneId.systemDefault()));
        }
        JSONArray vtimezones = new JSONArray();
        for (String tzid : zones) {
            ZoneId zone = Zones.zoneOf(tzid);
            if (zone != null && zone.getId().equals(tzid)) {
                vtimezones.put(Zones.vtimezone(zone, since));
            }
        }

        JSONObject edit = new JSONObject();
        edit.put("event", merged);
        edit.put("stamp", Zones.utc(System.currentTimeMillis()));
        edit.put("addresses", new JSONArray().put(target.owner));
        edit.put("vtimezones", vtimezones);
        return edit;
    }

    /** Every zone a view's times name. */
    private static void zones(Object node, Set<String> out) {
        if (node instanceof JSONObject) {
            JSONObject object = (JSONObject) node;
            if (EventTime.ZONED.equals(object.optString("kind"))) {
                out.add(object.optString("tzid"));
            }
            for (Iterator<String> keys = object.keys(); keys.hasNext(); ) {
                zones(object.opt(keys.next()), out);
            }
        } else if (node instanceof JSONArray) {
            JSONArray array = (JSONArray) node;
            for (int index = 0; index < array.length(); index++) {
                zones(array.opt(index), out);
            }
        }
    }

    /**
     * Services a push yield: a create projects the object onto new rows, an
     * update rewrites them in place, a remove deletes them; updates and
     * removes are conditioned on the revision the engine last agreed on and
     * report rejected on a mismatch, so a calendar-app edit racing the pass
     * is reconciled by the next one. A write the provider refuses for one
     * object leaves that object off the phone, logged, until it changes.
     * {@code pushed} is told how many changes are through after each one.
     */
    JSONObject push(String collection, JSONArray changes, IntConsumer pushed)
            throws JSONException {
        Target target = requireTarget(collection);
        JSONArray results = new JSONArray();
        for (int index = 0; index < changes.length(); index++) {
            JSONObject change = changes.getJSONObject(index);
            String handle = change.getString("handle");
            String ifMatch = change.isNull("ifMatch") ? null : change.optString("ifMatch", null);
            try {
                switch (change.getString("op")) {
                    case "add":
                    case "update":
                        results.put(pushWrite(target, collection, change, ifMatch));
                        break;
                    case "remove":
                        results.put(pushRemove(target, handle, ifMatch));
                        break;
                    default:
                        // NOTE: no flags on the phone spoke.
                        results.put(PimdirEngine.result(handle, true, null, null));
                        break;
                }
            } catch (Exception failure) {
                Log.w("pimalaya", "calendar phone push failed for " + handle, failure);
                results.put(PimdirEngine.result(handle, false, null, null));
            }
            pushed.accept(index + 1);
        }
        JSONObject reply = new JSONObject();
        reply.put("results", results);
        return reply;
    }

    /**
     * Pushes a create or a content change: the object's rows written in
     * place, a create landing under the name behind its provisional handle
     * (SYNC §2), and a create a crash left on the phone rewritten rather
     * than duplicated.
     */
    private JSONObject pushWrite(Target target, String collection, JSONObject change,
            String ifMatch) throws JSONException {
        String handle = change.getString("handle");
        boolean add = "add".equals(change.getString("op"));
        JSONObject row = offline.loadRow(collection, handle);
        if (row == null || row.getString("vcard").isEmpty()) {
            return PimdirEngine.result(handle, false, null, null);
        }
        String name = PimdirStorage.nameOf(handle);
        String body = row.getString("vcard");

        List<Row> existing = rows(target, name, true);
        if (!add && ifMatch != null && !existing.isEmpty()
                && !ifMatch.equals(revision(target, name))) {
            // NOTE: a calendar-app edit landed since the base; it stays for
            // the next pass rather than being overwritten.
            return PimdirEngine.result(handle, false, null, null);
        }
        try {
            if (!write(target, name, body, null)) {
                return PimdirEngine.result(handle, false, null, null);
            }
        } catch (IllegalArgumentException | IllegalStateException | SQLException
                | PimalayaException refused) {
            // NOTE: refused for this object alone (an unknown colour key, a
            // rule the provider cannot read, an object the native side cannot
            // parse): it stays in the store and off the phone, and is tried
            // again when it changes.
            Log.w("pimalaya", "calendar provider refused " + name, refused);
        }
        return PimdirEngine.result(handle, true, add ? name : null, PimdirHash.of(body));
    }

    /** Pushes a removal: the object's rows go, its exceptions named. */
    private JSONObject pushRemove(Target target, String handle, String ifMatch) {
        List<Row> rows = rows(target, handle, true);
        if (rows.isEmpty()) {
            return result(handle, true);
        }
        if (ifMatch != null && !ifMatch.equals(revision(target, handle))) {
            return result(handle, false);
        }
        purge(target, rows);
        return result(handle, true);
    }

    private static JSONObject result(String handle, boolean accepted) {
        try {
            return PimdirEngine.result(handle, accepted, null, null);
        } catch (JSONException error) {
            throw new IllegalStateException(error);
        }
    }

    /**
     * Writes an object's rows from its body, in place: the event rows
     * matched and updated, sub-rows diffed one by one ({@link
     * CalendarMapping#plan}), the convergence stamp in the same batch. Each
     * update is guarded on its row not being dirty, or, when {@code
     * observed} is given (putting back what a calendar app changed), on the
     * rows still being as observed. Answers whether the batch landed.
     */
    private boolean write(Target target, String handle, String body, List<Row> observed)
            throws JSONException {
        JSONObject view = EventViews.projectEvent(body, now());
        List<Row> desired = CalendarMapping.rows(view, phone(target, handle));
        String token = PimdirHash.of(body);
        String projected = projected(body);
        int exceptions = 0;
        for (Row row : desired) {
            exceptions += row.values.get(Events.ORIGINAL_INSTANCE_TIME) == null ? 0 : 1;
        }
        for (Row row : desired) {
            row.values.put(Events.DIRTY, 0);
            row.values.put(Events.DELETED, 0);
            row.values.put(Events.SYNC_DATA1, token);
            if (row.values.get(Events.ORIGINAL_INSTANCE_TIME) == null) {
                row.values.put(Events.SYNC_DATA2, String.valueOf(exceptions));
                row.values.put(Events.SYNC_DATA4, projected);
            }
        }

        List<Row> existing = observed == null ? rows(target, handle, true) : observed;
        List<Write> plan = CalendarMapping.plan(existing, desired);
        List<ContentProviderOperation> guards =
                observed == null ? List.of() : asserts(target, handle, observed);
        try {
            apply(guards, plan, target, observed == null);
            return true;
        } catch (OperationApplicationException moved) {
            return false;
        } catch (TransactionTooLargeException large) {
            return split(target, handle, plan, guards, body, observed);
        } catch (RemoteException failure) {
            throw new RuntimeException(failure);
        }
    }

    /**
     * Writes an object too large for one binder transaction: its new event
     * rows first, then, the object being all updates, the rest in parts,
     * each behind the guards and an assertion its rows are still clean. The
     * one case where the rows land in more than one transaction.
     */
    private boolean split(Target target, String handle, List<Write> plan,
            List<ContentProviderOperation> guards, String body, List<Row> observed)
            throws JSONException {
        List<Write> inserts = new ArrayList<>();
        Map<Integer, Integer> moved = new HashMap<>();
        for (int index = 0; index < plan.size(); index++) {
            Write write = plan.get(index);
            if (Write.EVENTS.equals(write.table) && "insert".equals(write.op)) {
                moved.put(index, inserts.size());
                inserts.add(
                        new Write(write.table, write.op, write.id,
                                write.parent < 0 ? -1 : moved.get(write.parent), write.event,
                                write.values));
            }
        }
        try {
            if (!inserts.isEmpty()) {
                apply(guards, inserts, target, true);
                return write(target, handle, body,
                        observed == null ? null : rows(target, handle, true));
            }

            List<ContentProviderOperation> clean = new ArrayList<>(guards);
            for (Write write : plan) {
                if (Write.EVENTS.equals(write.table) && "update".equals(write.op)) {
                    clean.add(
                            ContentProviderOperation.newAssertQuery(eventUri(target, write.id))
                                    .withSelection(CLEAN, null)
                                    .withExpectedCount(1)
                                    .build());
                }
            }
            for (int from = 0; from < plan.size(); from += PART) {
                apply(from == 0 ? guards : clean, plan.subList(from, Math.min(plan.size(),
                        from + PART)), target, observed == null);
            }
            return true;
        } catch (OperationApplicationException changed) {
            return false;
        } catch (RemoteException failure) {
            throw new RuntimeException(failure);
        }
    }

    /**
     * Stamps an object's rows converged, guarded on what was read: each row
     * still as observed, sub-rows included, and no exception row added or
     * gone; deleted exception rows purged in the same batch. Answers whether
     * it landed.
     */
    private boolean stamp(Target target, String handle, List<Row> observed, String token,
            String projected) {
        ArrayList<ContentProviderOperation> operations =
                new ArrayList<>(asserts(target, handle, observed));
        int exceptions = 0;
        for (Row row : observed) {
            if (!row.deleted && row.values.get(Events.ORIGINAL_INSTANCE_TIME) != null) {
                exceptions++;
            }
        }
        for (Row row : observed) {
            if (row.deleted) {
                operations.add(
                        ContentProviderOperation.newDelete(eventUri(target, row.id)).build());
                continue;
            }
            ContentProviderOperation.Builder update =
                    ContentProviderOperation.newUpdate(eventUri(target, row.id))
                            .withValue(Events.DIRTY, 0)
                            .withValue(Events.SYNC_DATA1, token)
                            .withValue(Events.SYNC_DATA3, row.id);
            if (row.values.get(Events.ORIGINAL_INSTANCE_TIME) == null) {
                update.withValue(Events.SYNC_DATA2, String.valueOf(exceptions))
                        .withValue(Events.SYNC_DATA4, projected);
            }
            operations.add(update.build());
        }
        try {
            batch(operations);
            return true;
        } catch (OperationApplicationException | RemoteException moved) {
            Log.d("pimalaya", "stamp of " + handle + " not landed: " + moved.getMessage());
            return false;
        }
    }

    /**
     * The assertions that an object's rows are as observed: each event row's
     * columns, its reminders, attendees and URL property, and the count of
     * rows the object has.
     */
    private List<ContentProviderOperation> asserts(Target target, String handle,
            List<Row> observed) {
        List<ContentProviderOperation> asserts = new ArrayList<>();
        asserts.add(
                ContentProviderOperation.newAssertQuery(eventsUri(target))
                        .withSelection(itemSelection(), itemArgs(target, handle))
                        .withExpectedCount(observed.size())
                        .build());
        for (Row row : observed) {
            ContentValues values = values(row.values);
            asserts.add(
                    ContentProviderOperation.newAssertQuery(eventUri(target, row.id))
                            .withValues(values)
                            .withExpectedCount(1)
                            .build());
            subAsserts(asserts, target, Reminders.CONTENT_URI, Reminders.EVENT_ID, row.id,
                    row.reminders);
            subAsserts(asserts, target, Attendees.CONTENT_URI, Attendees.EVENT_ID, row.id,
                    row.attendees);
        }
        return asserts;
    }

    private static void subAsserts(List<ContentProviderOperation> asserts, Target target,
            Uri table, String column, long event, List<Map<String, Object>> rows) {
        asserts.add(
                ContentProviderOperation.newAssertQuery(asSyncAdapter(table, target.account))
                        .withSelection(column + " = ?", new String[] {String.valueOf(event)})
                        .withExpectedCount(rows.size())
                        .build());
        for (Map<String, Object> row : rows) {
            Map<String, Object> values = new LinkedHashMap<>(row);
            Object id = values.remove("_id");
            asserts.add(
                    ContentProviderOperation.newAssertQuery(
                                    ContentUris.withAppendedId(
                                            asSyncAdapter(table, target.account),
                                            Long.parseLong(String.valueOf(id))))
                            .withValues(values(values))
                            .withExpectedCount(1)
                            .build());
        }
    }

    /**
     * A plan applied as one batch behind its guards, which the provider runs
     * as one transaction: the guards first, so they read the rows before any
     * write of the batch, every back reference counted past them.
     */
    private void apply(List<ContentProviderOperation> guards, List<Write> plan, Target target,
            boolean guarded) throws RemoteException, OperationApplicationException {
        ArrayList<ContentProviderOperation> batch = new ArrayList<>(guards);
        Map<Integer, Integer> at = new HashMap<>();
        List<Integer> inserted = new ArrayList<>();
        for (int index = 0; index < plan.size(); index++) {
            Write write = plan.get(index);
            Uri table = tableUri(target, write.table);
            Uri row = ContentUris.withAppendedId(table, write.id);
            Integer parent = write.parent < 0 ? null : at.get(write.parent);
            ContentProviderOperation.Builder builder;
            switch (write.op) {
                case "insert":
                    builder =
                            ContentProviderOperation.newInsert(table)
                                    .withValues(values(write.values));
                    if (Write.EVENTS.equals(write.table)) {
                        builder.withValue(Events.CALENDAR_ID, target.id);
                        if (parent != null) {
                            builder.withValueBackReference(Events.ORIGINAL_ID, parent);
                        }
                        inserted.add(batch.size());
                    } else if (parent != null) {
                        builder.withValueBackReference(eventColumn(write.table), parent);
                    } else {
                        builder.withValue(eventColumn(write.table), write.event);
                    }
                    break;
                case "update":
                    builder =
                            ContentProviderOperation.newUpdate(row)
                                    .withValues(values(write.values));
                    if (Write.EVENTS.equals(write.table)) {
                        builder.withValue(Events.SYNC_DATA3, write.id);
                        if (guarded) {
                            builder.withSelection(CLEAN, null).withExpectedCount(1);
                        }
                    }
                    break;
                default:
                    builder = ContentProviderOperation.newDelete(row);
                    break;
            }
            at.put(index, batch.size());
            batch.add(builder.build());
        }
        // NOTE: a new row's own id, known once the insert lands, is what
        // tells it from a clone the provider's split makes of it.
        for (int insert : inserted) {
            batch.add(
                    ContentProviderOperation.newUpdate(eventsUri(target))
                            .withSelection(Events._ID + " = ?", new String[] {""})
                            .withSelectionBackReference(0, insert)
                            .withValueBackReference(Events.SYNC_DATA3, insert)
                            .build());
        }
        batch(batch);
    }

    private void batch(ArrayList<ContentProviderOperation> batch)
            throws RemoteException, OperationApplicationException {
        if (!batch.isEmpty()) {
            context.getContentResolver().applyBatch(CalendarContract.AUTHORITY, batch);
        }
    }

    /** Deletes an object's rows, its exception rows named. */
    private void purge(Target target, List<Row> rows) {
        ArrayList<ContentProviderOperation> deletes = new ArrayList<>();
        for (Row row : rows) {
            deletes.add(ContentProviderOperation.newDelete(eventUri(target, row.id)).build());
        }
        try {
            batch(deletes);
        } catch (OperationApplicationException | RemoteException failure) {
            Log.w("pimalaya", "calendar purge failed", failure);
        }
    }

    private void purge(Target target, List<State> tops, List<State> exceptions) {
        List<Row> rows = new ArrayList<>();
        for (List<State> states : List.of(tops, exceptions)) {
            for (State state : states) {
                Row row = new Row();
                row.id = state.id;
                rows.add(row);
            }
        }
        purge(target, rows);
    }

    /** The revision an object's rows are at now, as a listing would give it. */
    private String revision(Target target, String handle) {
        List<State> states = states(target, handle);
        String token = null;
        boolean dirty = false;
        int live = 0;
        int count = 0;
        for (State state : states) {
            dirty |= state.dirty || state.deleted;
            if (state.top()) {
                token = state.token;
                count = state.count;
                dirty |= state.token == null;
            } else if (!state.deleted) {
                live++;
            }
        }
        if (!dirty && live >= count && token != null) {
            return token;
        }
        return DIRTY + CalendarMapping.fingerprint(rows(target, handle, false));
    }

    /**
     * The body the phone last converged on, the patch base of every read;
     * empty for an object created on the phone.
     */
    private String phoneBase(String collection, String handle) throws JSONException {
        JSONObject row = offline.loadRow(collection, handle);
        if (row == null) {
            return "";
        }
        String held = row.isNull("baseVcard") ? null : row.optString("baseVcard", null);
        if (held == null || held.isEmpty()) {
            // NOTE: never converged but the store holds it; its own body is the
            // closest base, keeping what the phone cannot hold.
            held = row.getString("vcard");
        }
        return held;
    }

    /**
     * What a projection depends on besides the object: the device's zone
     * for an object with a floating time, the window's month for a series
     * listed instance by instance. Null when neither.
     */
    private String projected(String body) {
        if (body.isEmpty()) {
            return null;
        }
        JSONObject view = EventViews.projectEvent(body, now());
        StringBuilder out = new StringBuilder();
        if (view.toString().contains("\"kind\":\"" + EventTime.FLOATING + "\"")) {
            out.append("zone=").append(ZoneId.systemDefault().getId());
        }
        if (view.optBoolean("listed")) {
            out.append(out.length() == 0 ? "" : ";").append("window=").append(month());
        }
        return out.length() == 0 ? null : out.toString();
    }

    /** Whether a row was projected for another zone or window than now's. */
    private static boolean stale(String projected) {
        if (projected == null) {
            return false;
        }
        for (String part : projected.split(";")) {
            if (part.startsWith("zone=")
                    && !part.substring(5).equals(ZoneId.systemDefault().getId())) {
                return true;
            }
            if (part.startsWith("window=") && !part.substring(7).equals(month())) {
                return true;
            }
        }
        return false;
    }

    /** The month the window of a listed series is placed by, which is when it moves. */
    private static String month() {
        return Zones.utc(System.currentTimeMillis()).substring(0, 6);
    }

    /** Now, a UTC stamp, which places the window of a listed series. */
    private static String now() {
        return Zones.utc(System.currentTimeMillis());
    }

    private static CalendarMapping.Phone phone(Target target, String handle) {
        return new CalendarMapping.Phone(handle, target.owner, ZoneId.systemDefault());
    }

    /** One event row's sync state. */
    private static final class State {
        long id;
        String syncId;
        Long originalId;
        String originalSyncId;
        Long instance;
        boolean dirty;
        boolean deleted;
        String token;

        /** The exception count stamped at convergence ({@code SYNC_DATA2}). */
        int count;

        /** The row's own id at convergence ({@code SYNC_DATA3}). */
        Long self;

        /** What it was projected for ({@code SYNC_DATA4}). */
        String projected;

        String uid;

        /** Stamped a handle by this listing. */
        boolean created;

        /** A master or a standalone row: no link to an original. */
        boolean top() {
            return originalId == null && originalSyncId == null && instance == null;
        }
    }

    /** The sync state of the calendar's rows, or of one object's. */
    private List<State> states(Target target, String handle) {
        List<State> states = new ArrayList<>();
        String selection = Events.CALENDAR_ID + " = ?";
        String[] args = {String.valueOf(target.id)};
        if (handle != null) {
            selection = itemSelection();
            args = itemArgs(target, handle);
        }
        try (Cursor cursor =
                context.getContentResolver().query(eventsUri(target), STATE, selection, args,
                        Events._ID)) {
            while (cursor != null && cursor.moveToNext()) {
                State state = new State();
                state.id = cursor.getLong(0);
                state.syncId = cursor.isNull(1) ? null : cursor.getString(1);
                state.originalId = cursor.isNull(2) ? null : cursor.getLong(2);
                state.originalSyncId = cursor.isNull(3) ? null : cursor.getString(3);
                state.instance = cursor.isNull(4) ? null : cursor.getLong(4);
                state.dirty = !cursor.isNull(5) && cursor.getInt(5) == 1;
                state.deleted = !cursor.isNull(6) && cursor.getInt(6) == 1;
                state.token = cursor.isNull(7) ? null : cursor.getString(7);
                state.count = cursor.isNull(8) ? 0 : parse(cursor.getString(8));
                state.self = cursor.isNull(9) ? null : (long) parse(cursor.getString(9));
                state.projected = cursor.isNull(10) ? null : cursor.getString(10);
                state.uid = cursor.isNull(11) ? null : cursor.getString(11);
                if (state.syncId != null && state.syncId.isEmpty()) {
                    state.syncId = null;
                }
                states.add(state);
            }
        }
        return states;
    }

    /** An object's rows, read for the mapping; deleted ones too when asked. */
    private List<Row> rows(Target target, String handle, boolean deleted) {
        String[] columns = new String[COLUMNS.length + 3];
        columns[0] = Events._ID;
        columns[1] = Events.DIRTY;
        columns[2] = Events.DELETED;
        System.arraycopy(COLUMNS, 0, columns, 3, COLUMNS.length);

        List<Row> rows = new ArrayList<>();
        Map<Long, Row> byId = new LinkedHashMap<>();
        try (Cursor cursor =
                context.getContentResolver()
                        .query(eventsUri(target), columns, itemSelection(),
                                itemArgs(target, handle), Events._ID)) {
            while (cursor != null && cursor.moveToNext()) {
                Row row = new Row();
                row.id = cursor.getLong(0);
                row.dirty = !cursor.isNull(1) && cursor.getInt(1) == 1;
                row.deleted = !cursor.isNull(2) && cursor.getInt(2) == 1;
                if (row.deleted && !deleted) {
                    continue;
                }
                // NOTE: the sync flags as they are, null included, which the
                // assertions of a stamp compare against.
                for (int column = 1; column < columns.length; column++) {
                    row.values.put(columns[column], valueOf(cursor, column));
                }
                rows.add(row);
                byId.put(row.id, row);
            }
        }
        if (byId.isEmpty()) {
            return rows;
        }

        String in = ids(byId.keySet());
        try (Cursor cursor =
                context.getContentResolver()
                        .query(
                                asSyncAdapter(Reminders.CONTENT_URI, target.account),
                                new String[] {
                                    Reminders._ID, Reminders.EVENT_ID, Reminders.MINUTES,
                                    Reminders.METHOD
                                },
                                Reminders.EVENT_ID + " IN (" + in + ")",
                                null,
                                Reminders._ID)) {
            while (cursor != null && cursor.moveToNext()) {
                Map<String, Object> reminder = new LinkedHashMap<>();
                reminder.put("_id", cursor.getLong(0));
                reminder.put(Reminders.MINUTES, valueOf(cursor, 2));
                reminder.put(Reminders.METHOD, valueOf(cursor, 3));
                byId.get(cursor.getLong(1)).reminders.add(reminder);
            }
        }
        try (Cursor cursor =
                context.getContentResolver()
                        .query(
                                asSyncAdapter(Attendees.CONTENT_URI, target.account),
                                new String[] {
                                    Attendees._ID,
                                    Attendees.EVENT_ID,
                                    Attendees.ATTENDEE_EMAIL,
                                    Attendees.ATTENDEE_NAME,
                                    Attendees.ATTENDEE_TYPE,
                                    Attendees.ATTENDEE_STATUS,
                                    Attendees.ATTENDEE_RELATIONSHIP
                                },
                                Attendees.EVENT_ID + " IN (" + in + ")",
                                null,
                                Attendees._ID)) {
            while (cursor != null && cursor.moveToNext()) {
                Map<String, Object> attendee = new LinkedHashMap<>();
                attendee.put("_id", cursor.getLong(0));
                attendee.put(Attendees.ATTENDEE_EMAIL, valueOf(cursor, 2));
                attendee.put(Attendees.ATTENDEE_NAME, valueOf(cursor, 3));
                attendee.put(Attendees.ATTENDEE_TYPE, valueOf(cursor, 4));
                attendee.put(Attendees.ATTENDEE_STATUS, valueOf(cursor, 5));
                attendee.put(Attendees.ATTENDEE_RELATIONSHIP, valueOf(cursor, 6));
                byId.get(cursor.getLong(1)).attendees.add(attendee);
            }
        }
        try (Cursor cursor =
                context.getContentResolver()
                        .query(
                                asSyncAdapter(ExtendedProperties.CONTENT_URI, target.account),
                                new String[] {
                                    ExtendedProperties._ID,
                                    ExtendedProperties.EVENT_ID,
                                    ExtendedProperties.VALUE
                                },
                                ExtendedProperties.EVENT_ID + " IN (" + in + ") AND "
                                        + ExtendedProperties.NAME + " = ?",
                                new String[] {CalendarMapping.URL},
                                ExtendedProperties._ID)) {
            while (cursor != null && cursor.moveToNext()) {
                Row row = byId.get(cursor.getLong(1));
                if (row.urlId < 0) {
                    row.urlId = cursor.getLong(0);
                    row.url = cursor.isNull(2) ? null : cursor.getString(2);
                }
            }
        }
        return rows;
    }

    /** The rows of one object: its master or standalone rows, and its exception rows. */
    private static String itemSelection() {
        return Events.CALENDAR_ID + " = ? AND (" + Events._SYNC_ID + " = ? OR "
                + Events.ORIGINAL_SYNC_ID + " = ? OR substr(" + Events._SYNC_ID + ", 1, ?) = ?)";
    }

    private static String[] itemArgs(Target target, String handle) {
        String prefix = handle + CalendarMapping.STANDALONE;
        return new String[] {
            String.valueOf(target.id), handle, handle, String.valueOf(prefix.length()), prefix
        };
    }

    private static String ids(Set<Long> ids) {
        StringBuilder out = new StringBuilder();
        for (long id : ids) {
            out.append(out.length() == 0 ? "" : ",").append(id);
        }
        return out.toString();
    }

    private static Object valueOf(Cursor cursor, int column) {
        switch (cursor.getType(column)) {
            case Cursor.FIELD_TYPE_NULL:
                return null;
            case Cursor.FIELD_TYPE_INTEGER:
                return cursor.getLong(column);
            case Cursor.FIELD_TYPE_FLOAT:
                return cursor.getDouble(column);
            default:
                return cursor.getString(column);
        }
    }

    private static ContentValues values(Map<String, Object> values) {
        ContentValues out = new ContentValues();
        for (Map.Entry<String, Object> entry : values.entrySet()) {
            Object value = entry.getValue();
            if (value == null) {
                out.putNull(entry.getKey());
            } else if (value instanceof Integer) {
                out.put(entry.getKey(), (Integer) value);
            } else if (value instanceof Long) {
                out.put(entry.getKey(), (Long) value);
            } else if (value instanceof Double) {
                out.put(entry.getKey(), (Double) value);
            } else {
                out.put(entry.getKey(), String.valueOf(value));
            }
        }
        return out;
    }

    private static int parse(String number) {
        try {
            return Integer.parseInt(number.trim());
        } catch (NumberFormatException unreadable) {
            return 0;
        }
    }

    private Target requireTarget(String collection) {
        Target target = target(collection);
        if (target == null) {
            throw new IllegalStateException("No phone calendar for " + collection);
        }
        return target;
    }

    private static Uri tableUri(Target target, String table) {
        switch (table) {
            case Write.REMINDERS:
                return asSyncAdapter(Reminders.CONTENT_URI, target.account);
            case Write.ATTENDEES:
                return asSyncAdapter(Attendees.CONTENT_URI, target.account);
            case Write.PROPERTIES:
                return asSyncAdapter(ExtendedProperties.CONTENT_URI, target.account);
            default:
                return eventsUri(target);
        }
    }

    private static String eventColumn(String table) {
        switch (table) {
            case Write.REMINDERS:
                return Reminders.EVENT_ID;
            case Write.ATTENDEES:
                return Attendees.EVENT_ID;
            default:
                return ExtendedProperties.EVENT_ID;
        }
    }

    private static Uri eventsUri(Target target) {
        return asSyncAdapter(Events.CONTENT_URI, target.account);
    }

    private static Uri eventUri(Target target, long id) {
        return ContentUris.withAppendedId(eventsUri(target), id);
    }

    private static Uri asSyncAdapter(Uri uri, Account account) {
        return CalendarRows.asSyncAdapter(uri, account);
    }
}
