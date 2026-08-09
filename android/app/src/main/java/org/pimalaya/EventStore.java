package org.pimalaya;

import android.content.Context;
import android.database.Cursor;

import org.pimalaya.client.Calendar;
import org.pimalaya.client.Event;

import java.util.ArrayList;
import java.util.List;

/**
 * The calendar side of the pimdir store: calendars as collections of kind
 * {@code text/calendar}, their objects as items.
 *
 * <p>It had its own database until the contacts moved: the argument then was
 * that read-only events had no use for the contacts schema's merge model, which
 * was true and is now moot, because pimdir's model is not the contacts one. A
 * calendar is a collection like any other and an event is an item like any
 * other; what mail and contacts add on top (bindings, staged edits, conflicts)
 * is simply unused here, at the cost of nothing.
 *
 * <p>Events are stored as the iCalendar text the server sent, unparsed: what an
 * event renders as depends on the window being shown, so the expansion happens
 * at render time through the bridge. The summary beside it ({@link PimdirMeta})
 * is what an agenda row reads, so listing a month never parses a body.
 *
 * <p>There is no subscription switch. The old schema carried one, defaulted to
 * true and never written by anything, so it decided nothing; when a calendar
 * picker exists it belongs beside the address books' switches, in the app's own
 * state rather than in the store.
 */
final class EventStore {
    private final PimdirItems items;
    private final PimdirCollections collections;
    private final PimdirAccount accounts;

    EventStore(Context context, PimdirDb store) {
        this.items = new PimdirItems(store);
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
                            calendar.color));
        }
        collections.replace(accountEmail, PimdirMeta.CALENDAR, listed);
    }

    /** Replaces one calendar collection's objects with the listed set. */
    void replaceEvents(String collectionId, List<Event> events) {
        List<PimdirItems.Row> rows = new ArrayList<>(events.size());
        for (Event event : events) {
            rows.add(
                    new PimdirItems.Row(
                            event.id,
                            event.ical,
                            // NOTE: the summary an agenda row renders is not
                            // derived here: it needs the expansion, which is the
                            // bridge's, so the item carries the body and the
                            // agenda projects it. What the meta does carry is
                            // the validator the server handed over, which is
                            // what lets an edit be pushed guarded instead of
                            // overwriting whatever arrived since.
                            PimdirMeta.calendarValidator(event.etag),
                            ""));
        }
        items.replace(collectionId, rows);
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

        StoredCalendar(String url, String accountEmail, String id, String name, String color) {
            this.url = url;
            this.accountEmail = accountEmail;
            this.id = id;
            this.name = name;
            this.color = color;
        }
    }

    List<StoredCalendar> loadCalendars() {
        List<StoredCalendar> calendars = new ArrayList<>();
        for (PimdirCollections.Stored stored : collections.list(PimdirMeta.CALENDAR)) {
            String account = accounts.idOf(stored.accountEmail);
            calendars.add(
                    new StoredCalendar(
                            PimdirAccount.nameOf(account, stored.id),
                            stored.accountEmail,
                            stored.id,
                            stored.name,
                            stored.color));
        }
        return calendars;
    }

    /** One stored calendar object, still iCalendar text. */
    static final class StoredEvent {
        /** The collection id of the calendar holding it. */
        final String collectionId;

        final String id;
        final String ical;

        /** The server's validator, empty when it sent none. */
        final String etag;

        StoredEvent(String collectionId, String id, String ical, String etag) {
            this.collectionId = collectionId;
            this.id = id;
            this.ical = ical;
            this.etag = etag;
        }
    }

    List<StoredEvent> loadEvents() {
        List<StoredEvent> events = new ArrayList<>();
        try (Cursor cursor =
                items.readable()
                        .rawQuery(
                                "SELECT i.collection, i.link_id, i.object_hash, i.meta FROM items i"
                                        + " JOIN collections c ON c.id = i.collection"
                                        + " WHERE c.kind = ? AND i.deleted = 0"
                                        + " AND i.retained_at IS NULL"
                                        + " AND i.object_hash IS NOT NULL",
                                new String[] {PimdirMeta.CALENDAR})) {
            while (cursor.moveToNext()) {
                events.add(
                        new StoredEvent(
                                cursor.getString(0),
                                cursor.getString(1),
                                items.body(cursor.getString(2)),
                                PimdirMeta.validatorOf(cursor.getString(3))));
            }
        }
        return events;
    }

    /**
     * Replaces one stored object's body after an edit, so the agenda
     * re-renders from what was just pushed rather than waiting for the
     * next sync to fetch it back.
     */
    void replaceEvent(String collectionId, String id, String ical, String etag) {
        android.database.sqlite.SQLiteDatabase db = items.writable();
        db.beginTransaction();
        try {
            items.put(
                    db,
                    collectionId,
                    new PimdirItems.Row(id, ical, PimdirMeta.calendarValidator(etag), ""));
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }
}
