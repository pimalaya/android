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
     * ({@link PimdirAccount}), the way a mailbox id is. A CalDAV URL is unique
     * on its own and would not need it, but a JMAP calendar id is unique only
     * within its JMAP account: two accounts on one provider both holding a
     * calendar {@code c1} would otherwise be one row that each sync round
     * re-points at the other account.
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
                            // agenda projects it. What is written is the key
                            // that orders it, empty until the first expansion
                            // teaches the store where the event starts.
                            null,
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

        StoredEvent(String collectionId, String id, String ical) {
            this.collectionId = collectionId;
            this.id = id;
            this.ical = ical;
        }
    }

    List<StoredEvent> loadEvents() {
        List<StoredEvent> events = new ArrayList<>();
        try (Cursor cursor =
                items.readable()
                        .rawQuery(
                                "SELECT i.collection, i.link_id, i.object_hash FROM items i"
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
                                items.body(cursor.getString(2))));
            }
        }
        return events;
    }
}
