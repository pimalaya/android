package org.pimalaya;

import android.content.ContentProvider;
import android.content.ContentProviderOperation;
import android.content.ContentProviderResult;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.OperationApplicationException;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.net.Uri;
import android.provider.CalendarContract;
import android.provider.CalendarContract.Events;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The calendar provider as far as the mirror relies on it, over an in-memory
 * SQLite: rows a calendar app writes come out dirty, rows the sync adapter
 * writes come out as written, an app deleting a master the adapter stamped
 * soft-deletes it and drops its unstamped exception rows, the adapter's own
 * deletes remove the row they name alone, and a batch is one transaction.
 * Columns are created as they are first named, so the tables hold whatever
 * the code under test writes.
 */
public class FakeCalendarProvider extends ContentProvider {
    private static final String[] TABLES = {
        "calendars", "events", "reminders", "attendees", "extendedproperties", "colors"
    };

    private SQLiteDatabase db;

    /** Every write a calendar app or the adapter made, counted. */
    int writes;

    @Override
    public boolean onCreate() {
        db = SQLiteDatabase.create(null);
        for (String table : TABLES) {
            db.execSQL("CREATE TABLE " + table + " (_id INTEGER PRIMARY KEY AUTOINCREMENT)");
        }
        columns("events", Events.CALENDAR_ID, Events._SYNC_ID, Events.ORIGINAL_ID,
                Events.ORIGINAL_SYNC_ID, Events.ORIGINAL_INSTANCE_TIME, Events.DIRTY,
                Events.DELETED, Events.SYNC_DATA1, Events.SYNC_DATA2, Events.SYNC_DATA3,
                Events.SYNC_DATA4, Events.UID_2445);
        columns("calendars", CalendarContract.Calendars.CAL_SYNC3);
        for (String table : new String[] {"reminders", "attendees", "extendedproperties"}) {
            columns(table, "event_id");
        }
        columns("extendedproperties", "name", "value");
        return true;
    }

    private static String table(Uri uri) {
        return uri.getPathSegments().get(0);
    }

    private static boolean adapter(Uri uri) {
        return uri.getBooleanQueryParameter(CalendarContract.CALLER_IS_SYNCADAPTER, false);
    }

    private static String selection(Uri uri, String selection) {
        if (uri.getPathSegments().size() < 2) {
            return selection;
        }
        String id = "_id = " + ContentUris.parseId(uri);
        return selection == null ? id : "(" + selection + ") AND " + id;
    }

    /** Adds the columns a statement names that the table does not have yet. */
    private void columns(String table, String... names) {
        Set<String> held = new HashSet<>();
        try (Cursor cursor = db.rawQuery("PRAGMA table_info(" + table + ")", null)) {
            while (cursor.moveToNext()) {
                held.add(cursor.getString(1).toLowerCase());
            }
        }
        for (String name : names) {
            if (name.matches("[A-Za-z_][A-Za-z0-9_]*") && held.add(name.toLowerCase())) {
                // NOTE: numeric affinity, so a number bound as text still matches,
                // as on the provider's typed columns.
                db.execSQL("ALTER TABLE " + table + " ADD COLUMN " + name + " NUMERIC");
            }
        }
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] args,
            String order) {
        String table = table(uri);
        if (projection != null) {
            columns(table, projection);
        }
        return db.query(table, projection, selection(uri, selection), args, null, null, order);
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        String table = table(uri);
        ContentValues row = new ContentValues(values);
        if (!adapter(uri)) {
            dirty(table, row);
        }
        columns(table, row.keySet().toArray(new String[0]));
        writes++;
        return ContentUris.withAppendedId(uri, db.insertOrThrow(table, null, row));
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] args) {
        String table = table(uri);
        ContentValues row = new ContentValues(values);
        String where = selection(uri, selection);
        if (!adapter(uri)) {
            if ("events".equals(table)) {
                row.put(Events.DIRTY, 1);
            } else {
                dirtyParents(table, where, args);
            }
        }
        columns(table, row.keySet().toArray(new String[0]));
        writes++;
        return db.update(table, row, where, args);
    }

    @Override
    public int delete(Uri uri, String selection, String[] args) {
        String table = table(uri);
        String where = selection(uri, selection);
        writes++;
        if (adapter(uri) || !"events".equals(table)) {
            if (!adapter(uri)) {
                dirtyParents(table, where, args);
            }
            return db.delete(table, where, args);
        }

        int deleted = 0;
        for (long id : ids("events", where, args)) {
            String syncId = null;
            try (Cursor cursor =
                    db.rawQuery("SELECT _sync_id FROM events WHERE _id = " + id, null)) {
                if (cursor.moveToFirst() && !cursor.isNull(0)) {
                    syncId = cursor.getString(0);
                }
            }
            db.delete("events", Events.ORIGINAL_ID + " = " + id + " AND _sync_id IS NULL", null);
            if (syncId == null) {
                db.delete("events", "_id = " + id, null);
            } else {
                ContentValues gone = new ContentValues();
                gone.put(Events.DELETED, 1);
                gone.put(Events.DIRTY, 1);
                db.update("events", gone, "_id = " + id, null);
            }
            deleted++;
        }
        return deleted;
    }

    @Override
    public ContentProviderResult[] applyBatch(ArrayList<ContentProviderOperation> operations)
            throws OperationApplicationException {
        db.beginTransaction();
        try {
            ContentProviderResult[] results = super.applyBatch(operations);
            db.setTransactionSuccessful();
            return results;
        } finally {
            db.endTransaction();
        }
    }

    @Override
    public String getType(Uri uri) {
        return null;
    }

    /** An app's insert: an event dirty, a sub-row's event dirty. */
    private void dirty(String table, ContentValues row) {
        if ("events".equals(table)) {
            row.put(Events.DIRTY, 1);
        } else if (row.containsKey("event_id")) {
            ContentValues dirty = new ContentValues();
            dirty.put(Events.DIRTY, 1);
            db.update("events", dirty, "_id = ?", new String[] {row.getAsString("event_id")});
        }
    }

    private void dirtyParents(String table, String where, String[] args) {
        if ("calendars".equals(table) || "colors".equals(table)) {
            return;
        }
        try (Cursor cursor =
                db.query(table, new String[] {"event_id"}, where, args, null, null, null)) {
            while (cursor.moveToNext()) {
                ContentValues dirty = new ContentValues();
                dirty.put(Events.DIRTY, 1);
                db.update("events", dirty, "_id = " + cursor.getLong(0), null);
            }
        }
    }

    private List<Long> ids(String table, String where, String[] args) {
        List<Long> ids = new ArrayList<>();
        try (Cursor cursor = db.query(table, new String[] {"_id"}, where, args, null, null, null)) {
            while (cursor.moveToNext()) {
                ids.add(cursor.getLong(0));
            }
        }
        return ids;
    }
}
