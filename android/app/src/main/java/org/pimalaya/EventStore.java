package org.pimalaya;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import org.pimalaya.client.Calendar;
import org.pimalaya.client.Event;

import java.util.ArrayList;
import java.util.List;

/**
 * The calendar side of the local store: subscribed calendars and their
 * raw calendar objects, one row per server resource.
 *
 * <p>Deliberately its own database rather than a {@code kind} column on
 * {@link CardStore}. The plan's step 4 called for that discriminator, but
 * the contacts schema carries a merge model (base bodies, conflict
 * revisions, dirty and deleted flags, a write-time index) that read-only
 * events have no use for, and both schemas are due to be replaced by
 * pimdir (P1-3). Widening the one that is about to go, to hold rows that
 * need none of its machinery, would have bought nothing.
 *
 * <p>Events are stored as the iCalendar text the server sent, unparsed:
 * what an event renders as depends on the window being shown, so the
 * expansion happens at render time through the bridge.
 */
final class EventStore extends SQLiteOpenHelper {
    private static final String DATABASE = "events.db";
    private static final int VERSION = 1;

    EventStore(Context context) {
        super(context, DATABASE, null, VERSION);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL(
                "CREATE TABLE IF NOT EXISTS calendar ("
                        + "url TEXT PRIMARY KEY, "
                        + "account_email TEXT NOT NULL, "
                        + "id TEXT NOT NULL, "
                        + "name TEXT NOT NULL, "
                        + "color TEXT, "
                        + "subscribed INTEGER NOT NULL DEFAULT 1)");
        db.execSQL(
                "CREATE TABLE IF NOT EXISTS event ("
                        + "calendar_url TEXT NOT NULL, "
                        + "id TEXT NOT NULL, "
                        + "etag TEXT, "
                        + "ical TEXT NOT NULL, "
                        + "PRIMARY KEY (calendar_url, id))");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int from, int to) {
        // NOTE: the store is a cache of a read-only sync, so an upgrade
        // just refetches rather than migrating.
        db.execSQL("DROP TABLE IF EXISTS event");
        db.execSQL("DROP TABLE IF EXISTS calendar");
        onCreate(db);
    }

    /**
     * Replaces an account's calendars with what the server just listed,
     * keeping the subscribed flag of the ones already known so a
     * refresh never silently re-enables a calendar the user turned off.
     */
    void replaceCalendars(String accountEmail, List<Calendar> calendars) {
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            for (Calendar calendar : calendars) {
                ContentValues values = new ContentValues();
                values.put("url", calendar.url);
                values.put("account_email", accountEmail);
                values.put("id", calendar.id);
                values.put("name", calendar.name);
                values.put("color", calendar.color);
                if (!known(db, calendar.url)) {
                    values.put("subscribed", 1);
                }
                db.insertWithOnConflict(
                        "calendar", null, values, SQLiteDatabase.CONFLICT_REPLACE);
            }
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    private static boolean known(SQLiteDatabase db, String url) {
        try (Cursor cursor =
                db.query("calendar", new String[] {"url"}, "url = ?", new String[] {url},
                        null, null, null)) {
            return cursor.moveToFirst();
        }
    }

    /** Replaces one calendar's events with the listed set. */
    void replaceEvents(String calendarUrl, List<Event> events) {
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            db.delete("event", "calendar_url = ?", new String[] {calendarUrl});
            for (Event event : events) {
                ContentValues values = new ContentValues();
                values.put("calendar_url", calendarUrl);
                values.put("id", event.id);
                values.put("etag", event.etag);
                values.put("ical", event.ical);
                db.insertWithOnConflict("event", null, values, SQLiteDatabase.CONFLICT_REPLACE);
            }
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    /** One stored calendar, with the account it belongs to. */
    static final class StoredCalendar {
        final String url;
        final String accountEmail;
        final String id;
        final String name;
        final String color;
        final boolean subscribed;

        StoredCalendar(
                String url,
                String accountEmail,
                String id,
                String name,
                String color,
                boolean subscribed) {
            this.url = url;
            this.accountEmail = accountEmail;
            this.id = id;
            this.name = name;
            this.color = color;
            this.subscribed = subscribed;
        }
    }

    List<StoredCalendar> loadCalendars() {
        List<StoredCalendar> calendars = new ArrayList<>();
        try (Cursor cursor =
                getReadableDatabase()
                        .query("calendar", null, null, null, null, null, "name COLLATE NOCASE")) {
            while (cursor.moveToNext()) {
                calendars.add(
                        new StoredCalendar(
                                text(cursor, "url"),
                                text(cursor, "account_email"),
                                text(cursor, "id"),
                                text(cursor, "name"),
                                text(cursor, "color"),
                                cursor.getInt(cursor.getColumnIndexOrThrow("subscribed")) != 0));
            }
        }
        return calendars;
    }

    /** One stored calendar object, still iCalendar text. */
    static final class StoredEvent {
        final String calendarUrl;
        final String id;
        final String ical;

        StoredEvent(String calendarUrl, String id, String ical) {
            this.calendarUrl = calendarUrl;
            this.id = id;
            this.ical = ical;
        }
    }

    List<StoredEvent> loadEvents() {
        List<StoredEvent> events = new ArrayList<>();
        try (Cursor cursor = getReadableDatabase().query("event", null, null, null, null, null,
                null)) {
            while (cursor.moveToNext()) {
                events.add(
                        new StoredEvent(
                                text(cursor, "calendar_url"),
                                text(cursor, "id"),
                                text(cursor, "ical")));
            }
        }
        return events;
    }

    private static String text(Cursor cursor, String column) {
        int index = cursor.getColumnIndexOrThrow(column);
        return cursor.isNull(index) ? null : cursor.getString(index);
    }
}
