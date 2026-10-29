package org.pimalaya.calendar;

import android.content.Context;
import android.database.Cursor;

import org.json.JSONException;
import org.json.JSONObject;

import io.requery.android.database.sqlite.SQLiteDatabase;

import org.pimalaya.client.Calendar;
import org.pimalaya.client.Event;
import org.pimalaya.client.PimdirSql;
import org.pimalaya.pimdir.ItemLinks;
import org.pimalaya.pimdir.PimdirAccount;
import org.pimalaya.pimdir.PimdirCollections;
import org.pimalaya.pimdir.PimdirDb;
import org.pimalaya.pimdir.PimdirItems;
import org.pimalaya.pimdir.PimdirStorage;
import org.pimalaya.pimdir.PimdirSummary;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The calendar side of the pimdir store: {@code text/calendar} collections.
 *
 * <p>Objects are the server's iCalendar text, no summary: what renders depends
 * on the window, so the bridge expands at render time. {@link CalendarEngine}
 * writes them (staged edits survive a refresh); here, the roster and reads.
 */
public final class EventStore {
    /**
     * The capability a source states whether a calendar takes to-dos by
     * (pimdir STORAGE §15.6).
     */
    static final String X_VTODO = "x-vtodo";

    /** The rows one canonical page reads. */
    private static final int PAGE = 500;

    private final PimdirItems items;
    private final PimdirStorage storage;
    private final PimdirCollections collections;
    private final PimdirAccount accounts;
    private final PimdirDb store;

    /** The calendar store over the shared pimdir store. */
    public EventStore(Context context, PimdirDb store) {
        this.store = store;
        this.items = new PimdirItems(store);
        this.storage = new PimdirStorage(store);
        this.collections = new PimdirCollections(store, context);
        this.accounts = new PimdirAccount(context);
    }

    /**
     * Replaces an account's calendars with what the server just listed.
     *
     * <p>Ids are addresses namespaced by the account ({@link PimdirAccount}),
     * as mailbox ids are: no calendar backend needs it, but a mailbox name is
     * unique only in its account, and one rule beats an exception.
     */
    public void replaceCalendars(String accountEmail, List<Calendar> calendars) {
        String account = accounts.idOf(accountEmail);

        List<PimdirCollections.Stored> listed = new ArrayList<>(calendars.size());
        for (Calendar calendar : calendars) {
            listed.add(
                    new PimdirCollections.Stored(
                            PimdirAccount.collectionId(account, calendar.url()),
                            accountEmail,
                            calendar.name(),
                            calendar.description(),
                            calendar.color(),
                            calendar.role(),
                            calendar.writable()));
        }
        collections.replace(accountEmail, PimdirSummary.CALENDAR, listed);
        declareTasks(account, calendars);
    }

    /**
     * Declares, for each CalDAV calendar listed, whether its server takes a
     * to-do there (pimdir STORAGE §15.6).
     *
     * <p>{@code x-vtodo} {@code none} for one whose
     * {@code supported-calendar-component-set} names components but not
     * {@code VTODO}, {@code full} otherwise, on the server source's row of
     * that collection, through the canonical {@code set_capability}. A row
     * per collection rather than the source's whole declaration: the
     * server source is every account's, and a calendar listed again
     * restates its own.
     */
    private void declareTasks(String account, List<Calendar> calendars) {
        SQLiteDatabase db = items.writable();
        db.beginTransaction();
        try {
            for (Calendar calendar : calendars) {
                if (!isCaldav(calendar.url())) {
                    continue;
                }
                boolean refused = Boolean.FALSE.equals(calendar.vtodo());
                Map<String, Object> values = new HashMap<>();
                values.put("account", account);
                values.put("source", PimdirStorage.SERVER);
                values.put("collection", PimdirAccount.collectionId(account, calendar.url()));
                values.put("capability", X_VTODO);
                values.put("support", refused ? "none" : "full");
                values.put(
                        "detail",
                        refused ? "its supported-calendar-component-set names no VTODO" : null);
                PimdirSql.Bound bound = PimdirSql.bind("SET_CAPABILITY", values);
                db.execSQL(bound.sql, bound.args);
            }
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    /** Whether a calendar address is served over CalDAV (RFC 4791). */
    public static boolean isCaldav(String url) {
        String address = url == null ? "" : url;
        return address.startsWith("https://") || address.startsWith("http://");
    }

    /**
     * Whether a source syncing the collection states {@code x-vtodo}
     * {@code none} there, or source-wide (pimdir STORAGE §15.6).
     *
     * <p>Through the canonical {@code load_capabilities}, as io-pimdir's
     * {@code accepts_vtodo} reads it: a source stating nothing refuses
     * nothing.
     */
    private boolean refusesTasks(String collection) {
        PimdirSql.Bound bound =
                PimdirSql.bind("LOAD_CAPABILITIES", Map.of("collection", collection));
        try (Cursor cursor = PimdirDb.typed(items.readable(), bound.sql, bound.args)) {
            while (cursor.moveToNext()) {
                if (X_VTODO.equals(cursor.getString(1)) && "none".equals(cursor.getString(2))) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Drops one account's calendars and their events. */
    public void forget(String accountEmail) {
        collections.replace(accountEmail, PimdirSummary.CALENDAR, List.of());
    }

    /** One stored calendar, with the account it belongs to. */
    public static final class StoredCalendar {
        /** The backend's own address, what a listing round asks for. */
        public final String url;

        /** The address of the account holding it. */
        public final String accountEmail;

        /** The namespaced collection id, what the store keys items by. */
        public final String id;

        /** Its display name. */
        public final String name;
        public final String color;

        /** Whether its source names it the account's default calendar. */
        public final boolean isDefault;

        /** Whether the user may write events into it. */
        public final boolean writable;

        /**
         * Whether a source syncing it states it takes no to-do ({@code
         * x-vtodo} {@code none}, pimdir STORAGE §15.6).
         */
        public final boolean refusesTasks;

        StoredCalendar(
                String url,
                String accountEmail,
                String id,
                String name,
                String color,
                boolean isDefault,
                boolean writable,
                boolean refusesTasks) {
            this.url = url;
            this.accountEmail = accountEmail;
            this.id = id;
            this.name = name;
            this.color = color;
            this.isDefault = isDefault;
            this.writable = writable;
            this.refusesTasks = refusesTasks;
        }
    }

    /** Every stored calendar, with its account. */
    public List<StoredCalendar> loadCalendars() {
        List<StoredCalendar> calendars = new ArrayList<>();
        for (PimdirCollections.Stored stored : collections.list(PimdirSummary.CALENDAR)) {
            String account = accounts.idOf(stored.accountEmail);
            calendars.add(
                    new StoredCalendar(
                            PimdirAccount.nameOf(account, stored.id),
                            stored.accountEmail,
                            stored.id,
                            stored.name,
                            stored.color,
                            stored.isDefault(),
                            stored.writable,
                            refusesTasks(stored.id)));
        }
        return calendars;
    }

    /** One stored calendar object, still iCalendar text. */
    public static final class StoredEvent {
        /** The collection id of the calendar holding it. */
        public final String collectionId;

        /** Its link id. */
        public final String id;

        /**
         * How the engine addresses the entry (SYNC §2).
         *
         * <p>The resource name the server bound it under, or the provisional
         * handle a create waits under until a push assigns one.
         */
        public final String handle;

        /** The iCalendar text, as the source sent it. */
        public final String ical;

        /** The server's validator, empty when it sent none. */
        final String etag;

        /** Whether a source holds it changed differently since their base. */
        final boolean conflicted;

        StoredEvent(String collectionId, String id, String handle, String ical, String etag) {
            this(collectionId, id, handle, ical, etag, false);
        }

        StoredEvent(
                String collectionId,
                String id,
                String handle,
                String ical,
                String etag,
                boolean conflicted) {
            this.collectionId = collectionId;
            this.id = id;
            this.handle = handle;
            this.ical = ical;
            this.etag = etag;
            this.conflicted = conflicted;
        }
    }

    /** The links endpoint of an agenda row's object. */
    public static ItemLinks.Endpoint endpointOf(StoredEvent event) {
        return ItemLinks.of(PimdirSummary.CALENDAR, event.id);
    }

    /** Every stored object, conflicted ones marked. */
    public List<StoredEvent> loadEvents() {
        return loadEvents(key -> true);
    }

    /**
     * Every stored to-do, conflicted ones marked: the items holding a
     * {@code task_summary} row (cairn/changes/gtd-hardening).
     *
     * <p>Found through the canonical {@code list_tasks_page_asc}, so only
     * their bodies are read; a calendar item holding no summary row of any
     * calendar kind, as a store written before summaries holds, has one
     * derived first ({@link #derive}). Off the main thread.
     */
    public List<StoredEvent> loadTasks() {
        Set<String> tasks = new HashSet<>();
        Map<String, String> missing = new HashMap<>();
        for (PimdirCollections.Stored calendar : collections.list(PimdirSummary.CALENDAR)) {
            Map<String, String> unsummarized = new HashMap<>();
            page(calendar.id, "LIST_TASKS_PAGE_ASC", tasks, unsummarized);
            Set<String> others = new HashSet<>();
            page(calendar.id, "LIST_EVENTS_PAGE_ASC", others, new HashMap<>());
            page(calendar.id, "LIST_JOURNALS_PAGE_ASC", others, new HashMap<>());
            unsummarized.keySet().removeAll(others);
            missing.putAll(unsummarized);
        }
        if (!missing.isEmpty()) {
            tasks.addAll(derive(missing));
        }
        return loadEvents(tasks::contains);
    }

    /**
     * Pages one collection through a canonical {@code list_*_page_asc},
     * keying each live item {@code collection\nlink_id}: into {@code
     * summarized} when its kind's row is held, else into {@code
     * unsummarized} with its body's hash, when it holds one.
     */
    private void page(
            String collection,
            String statement,
            Set<String> summarized,
            Map<String, String> unsummarized) {
        Map<String, Object> values = new HashMap<>();
        values.put("collection", collection);
        values.put("after_key", "");
        values.put("after_seq", -1L);
        values.put("limit", (long) PAGE);
        SQLiteDatabase db = items.readable();
        for (int read = PAGE; read == PAGE; ) {
            read = 0;
            PimdirSql.Bound bound = PimdirSql.bind(statement, values);
            try (Cursor cursor = PimdirDb.typed(db, bound.sql, bound.args)) {
                while (cursor.moveToNext()) {
                    read++;
                    String key = collection + "\n" + cursor.getString(1);
                    // NOTE: every statement of the family reads the kind's
                    // NOT NULL summary text as its eighth column, NULL
                    // for an item holding no row of that kind.
                    if (!cursor.isNull(7)) {
                        summarized.add(key);
                    } else if (!cursor.isNull(3)) {
                        unsummarized.put(key, cursor.getString(3));
                    }
                    values.put("after_key", cursor.getString(4));
                    values.put("after_seq", cursor.getLong(0));
                }
            }
        }
    }

    /**
     * Derives and writes the summary of calendar items holding none,
     * answering the keys of those that are to-dos.
     *
     * <p>Once for a store whose items were written before summaries were
     * (spec/offline-store.md): every writer derives one since. Under the
     * store's writer, in one transaction; a body that derives nothing is
     * left as it is.
     */
    private Set<String> derive(Map<String, String> missing) {
        Set<String> tasks = new HashSet<>();
        synchronized (store.locks().store) {
            SQLiteDatabase db = items.writable();
            db.beginTransaction();
            try {
                for (Map.Entry<String, String> item : missing.entrySet()) {
                    String[] key = item.getKey().split("\n", 2);
                    org.pimalaya.client.Derived derived =
                            CalendarSummaries.of(items.body(item.getValue()));
                    if (derived == null || derived.summary() == null) {
                        continue;
                    }
                    PimdirSummary.write(db, key[0], key[1], derived.summary());
                    if (derived.summary().has("task")) {
                        tasks.add(item.getKey());
                    }
                }
                db.setTransactionSuccessful();
            } finally {
                db.endTransaction();
            }
        }
        return tasks;
    }

    /**
     * Every stored object whose key {@code wanted} takes, conflicted ones
     * marked.
     */
    private List<StoredEvent> loadEvents(java.util.function.Predicate<String> wanted) {
        List<StoredEvent> events = new ArrayList<>();
        Set<String> conflicted = conflicted();
        try (Cursor cursor =
                items.readable()
                        .rawQuery(
                                "SELECT i.collection, i.link_id, i.object_hash, b.base_revision,"
                                        + " b.handle"
                                        + " FROM items i"
                                        + " JOIN collections c ON c.id = i.collection"
                                        + " LEFT JOIN bindings b ON b.collection = i.collection"
                                        + " AND b.link_id = i.link_id AND b.source = ?"
                                        + " WHERE c.kind = ? AND i.deleted = 0"
                                        + " AND i.retained_at IS NULL"
                                        + " AND i.object_hash IS NOT NULL",
                                new String[] {PimdirStorage.SERVER, PimdirSummary.CALENDAR})) {
            while (cursor.moveToNext()) {
                String id = cursor.getString(1);
                if (!wanted.test(cursor.getString(0) + "\n" + id)) {
                    continue;
                }
                events.add(
                        new StoredEvent(
                                cursor.getString(0),
                                id,
                                PimdirStorage.rowHandle(cursor.isNull(4) ? null : cursor.getString(4), id),
                                items.body(cursor.getString(2)),
                                cursor.isNull(3) ? "" : cursor.getString(3),
                                conflicted.contains(cursor.getString(0) + "\n" + id)));
            }
        }
        return events;
    }

    /**
     * The entries conflicted on any source of any calendar.
     *
     * <p>As their collection and link id, which the agenda marks.
     */
    private Set<String> conflicted() {
        Set<String> conflicted = new HashSet<>();
        for (PimdirCollections.Stored stored : collections.list(PimdirSummary.CALENDAR)) {
            for (String source : sourcesOf(stored.id)) {
                for (StoredConflict conflict : conflictsOf(storage, source)) {
                    conflicted.add(stored.id + "\n" + conflict.linkId);
                }
            }
        }
        return conflicted;
    }

    /**
     * One entry conflicted on one source of its calendar (spec/conflicts.md).
     *
     * <p>Both sides edited it since the base they agreed on; holds the three
     * bodies its merge reads, from the store alone.
     */
    static final class StoredConflict {
        /**
         * The engine collection the conflicted binding is on.
         *
         * <p>The calendar's own id for its server, its phone source's
         * otherwise. A resolution is staged there.
         */
        final String collection;

        /** How that source addresses the entry. */
        final String handle;

        final String linkId;

        /** The body both sides last agreed on; empty for none. */
        final String base;

        /** The body staged here. */
        final String local;

        /** The body the source holds, recorded when the conflict was. */
        final String remote;

        StoredConflict(
                String collection,
                String handle,
                String linkId,
                String base,
                String local,
                String remote) {
            this.collection = collection;
            this.handle = handle;
            this.linkId = linkId;
            this.base = base;
            this.local = local;
            this.remote = remote;
        }
    }

    /**
     * The engine collections one calendar is reconciled under.
     *
     * <p>Its server, then its phone source, whose binding conflicts the same
     * way.
     */
    static List<String> sourcesOf(String collectionId) {
        return List.of(collectionId, PimdirStorage.phoneCollection(collectionId));
    }

    /** An agenda row's conflict, on its first source holding one; else null. */
    StoredConflict conflictOf(StoredEvent event) {
        for (String source : sourcesOf(event.collectionId)) {
            for (StoredConflict conflict : conflictsOf(storage, source)) {
                if (conflict.linkId.equals(event.id)) {
                    return conflict;
                }
            }
        }
        return null;
    }

    /**
     * One source's conflicted entries whose diverging body landed.
     *
     * <p>The only ones a merge can read ({@link PimdirStorage#loadConflicts}).
     */
    static List<StoredConflict> conflictsOf(PimdirStorage storage, String engineCollection) {
        List<StoredConflict> conflicts = new ArrayList<>();
        try {
            for (JSONObject conflict : storage.loadConflicts(engineCollection)) {
                String local = conflict.optString("vcard");
                if (local.isEmpty()) {
                    continue;
                }
                conflicts.add(
                        new StoredConflict(
                                engineCollection,
                                conflict.getString("handle"),
                                conflict.getString("id"),
                                conflict.optString("baseVcard"),
                                local,
                                conflict.getString("remoteVcard")));
            }
        } catch (JSONException error) {
            throw new IllegalStateException(error);
        }
        return conflicts;
    }
}
