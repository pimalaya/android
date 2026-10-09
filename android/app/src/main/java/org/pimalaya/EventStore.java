package org.pimalaya;

import android.content.Context;
import android.database.Cursor;

import org.json.JSONException;
import org.json.JSONObject;

import org.pimalaya.client.Calendar;
import org.pimalaya.client.Event;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The calendar side of the pimdir store: calendars as collections of kind
 * {@code text/calendar}, their objects as items.
 *
 * <p>It had its own database until the contacts moved: the argument then was
 * that read-only events had no use for the contacts schema's merge model, which
 * was true and is now moot, because pimdir's model is not the contacts one. A
 * calendar is a collection like any other and an event is an item like any
 * other, bindings, staged edits and conflicts included.
 *
 * <p>Events are stored as the iCalendar text the server sent, unparsed: what an
 * event renders as depends on the window being shown, so the expansion happens
 * at render time through the bridge. No summary is written beside it: what an
 * agenda row shows needs that same expansion, so the row carries the body and
 * the validator its next write is guarded by, and nothing else.
 *
 * <p>The objects themselves are written by {@link CalendarEngine} and never
 * here: a calendar is reconciled rather than replaced, which is what lets a
 * staged create, edit or delete survive a refresh. What is left here is the
 * calendar roster and the two reads the agenda does.
 *
 * <p>There is no subscription switch. The old schema carried one, defaulted to
 * true and never written by anything, so it decided nothing; when a calendar
 * picker exists it belongs beside the address books' switches, in the app's own
 * state rather than in the store.
 */
final class EventStore {
    private final PimdirItems items;
    private final PimdirStorage storage;
    private final PimdirCollections collections;
    private final PimdirAccount accounts;
    private final Context context;

    EventStore(Context context, PimdirDb store) {
        this.context = context;
        this.items = new PimdirItems(store);
        this.storage = new PimdirStorage(store);
        this.collections = new PimdirCollections(store, context);
        this.accounts = new PimdirAccount(context);
    }

    /**
     * Replaces an account's calendars with what the server just listed.
     *
     * <p>The collection id is the calendar's address namespaced by the account
     * ({@link PimdirAccount}), the way a mailbox id is. Neither backend needs
     * it today: a CalDAV URL is unique on its own, and a JMAP calendar URL
     * carries its JMAP account id since the address itself was fixed. It stays
     * because mailboxes do need it, a mailbox name being unique only within
     * its account, and one rule across the three domains beats an exception.
     */
    void replaceCalendars(String accountEmail, List<Calendar> calendars) {
        String account = accounts.idOf(accountEmail);

        List<PimdirCollections.Stored> listed = new ArrayList<>(calendars.size());
        for (Calendar calendar : calendars) {
            listed.add(
                    new PimdirCollections.Stored(
                            PimdirAccount.collectionId(account, calendar.url),
                            accountEmail,
                            calendar.name,
                            calendar.description,
                            calendar.color,
                            calendar.role,
                            calendar.writable));
        }
        collections.replace(accountEmail, PimdirSummary.CALENDAR, listed);
    }

    /** Drops one account's calendars and their events. */
    void forget(String accountEmail) {
        collections.replace(accountEmail, PimdirSummary.CALENDAR, List.of());
    }

    /** One stored calendar, with the account it belongs to. */
    static final class StoredCalendar {
        /** The backend's own address, what a listing round asks for. */
        final String url;

        final String accountEmail;

        /** The namespaced collection id, what the store keys items by. */
        final String id;

        final String name;
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

    List<StoredCalendar> loadCalendars() {
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
    static final class StoredEvent {
        /** The collection id of the calendar holding it. */
        final String collectionId;

        final String id;

        /**
         * How the engine addresses the entry: the resource name the server
         * bound it under, or the provisional handle a create waits under
         * until a push assigns one (SYNC §2).
         */
        final String handle;

        final String ical;

        /** The server's validator, empty when it sent none. */
        final String etag;

        /** Whether it holds a change the server has not taken yet. */
        final boolean unsynced;

        /** Whether the server refused that change for good. */
        final boolean refused;

        /** Whether a source holds it changed differently since their base. */
        final boolean conflicted;

        StoredEvent(String collectionId, String id, String handle, String ical, String etag) {
            this(collectionId, id, handle, ical, etag, false, false, false);
        }

        StoredEvent(
                String collectionId,
                String id,
                String handle,
                String ical,
                String etag,
                boolean unsynced,
                boolean refused,
                boolean conflicted) {
            this.collectionId = collectionId;
            this.id = id;
            this.handle = handle;
            this.ical = ical;
            this.etag = etag;
            this.unsynced = unsynced;
            this.refused = refused;
            this.conflicted = conflicted;
        }
    }

    List<StoredEvent> loadEvents() {
        List<StoredEvent> events = new ArrayList<>();
        Set<String> conflicted = conflicted();
        try (Cursor cursor =
                items.readable()
                        .rawQuery(
                                "SELECT i.collection, i.link_id, i.object_hash, b.base_revision,"
                                        + " b.handle, b.base_present, b.base_object"
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
                // NOTE: a create no push carried out, or a body past the
                // one the server agreed on.
                boolean unsynced =
                        cursor.isNull(4)
                                || cursor.getInt(5) == 0
                                || !cursor.getString(2).equals(cursor.getString(6));
                events.add(
                        new StoredEvent(
                                cursor.getString(0),
                                id,
                                CardStore.rowHandle(cursor.isNull(4) ? null : cursor.getString(4), id),
                                items.body(cursor.getString(2)),
                                cursor.isNull(3) ? "" : cursor.getString(3),
                                unsynced,
                                unsynced && Refusals.refused(context, cursor.getString(0), id),
                                conflicted.contains(cursor.getString(0) + "\n" + id)));
            }
        }
        return events;
    }

    /** How many entries a source holds changed differently, left for their page. */
    int conflictCount() {
        return conflicted().size();
    }

    /**
     * The entries conflicted on any source of any calendar, as their
     * collection and link id, which the agenda marks.
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
     * One entry conflicted on one source of its calendar: both sides
     * edited it since the base they agreed on, and the three bodies its
     * merge reads, from the store alone (spec/conflicts.md).
     */
    static final class StoredConflict {
        /**
         * The engine collection the conflicted binding is on: the
         * calendar's own id for its server, its phone source's otherwise.
         * A resolution is staged there.
         */
        final String collection;

        /** How that source addresses the entry. */
        final String handle;

        final String linkId;

        /** The body both sides last agreed on, empty when they agreed on none. */
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
     * The engine collections one calendar is reconciled under: its server,
     * then its phone source, whose binding conflicts the same way.
     */
    static List<String> sourcesOf(String collectionId) {
        return List.of(collectionId, PimdirStorage.phoneCollection(collectionId));
    }

    /**
     * The conflict an agenda row stands for, on the first of its sources
     * holding one; null when none does.
     */
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
     * One source's conflicted entries whose diverging body has landed, the
     * only ones a merge can read ({@link PimdirStorage#loadConflicts}).
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
