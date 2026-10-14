package org.pimalaya.calendar;

import android.content.Context;
import android.database.Cursor;

import org.json.JSONException;
import org.json.JSONObject;

import org.pimalaya.client.Calendar;
import org.pimalaya.client.Event;
import org.pimalaya.pimdir.ItemLinks;
import org.pimalaya.pimdir.PimdirAccount;
import org.pimalaya.pimdir.PimdirCollections;
import org.pimalaya.pimdir.PimdirDb;
import org.pimalaya.pimdir.PimdirItems;
import org.pimalaya.pimdir.PimdirStorage;
import org.pimalaya.pimdir.PimdirSummary;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The calendar side of the pimdir store: {@code text/calendar} collections.
 *
 * <p>Objects are the server's iCalendar text, no summary: what renders depends
 * on the window, so the bridge expands at render time. {@link CalendarEngine}
 * writes them (staged edits survive a refresh); here, the roster and reads.
 */
public final class EventStore {
    private final PimdirItems items;
    private final PimdirStorage storage;
    private final PimdirCollections collections;
    private final PimdirAccount accounts;

    /** The calendar store over the shared pimdir store. */
    public EventStore(Context context, PimdirDb store) {
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
        final String color;

        /** Whether its source names it the account's default calendar. */
        final boolean isDefault;

        /** Whether the user may write events into it. */
        final boolean writable;

        StoredCalendar(
                String url,
                String accountEmail,
                String id,
                String name,
                String color,
                boolean isDefault,
                boolean writable) {
            this.url = url;
            this.accountEmail = accountEmail;
            this.id = id;
            this.name = name;
            this.color = color;
            this.isDefault = isDefault;
            this.writable = writable;
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
                            stored.writable));
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

    /** How many entries conflict on a source, left for their page. */
    int conflictCount() {
        return conflicted().size();
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
