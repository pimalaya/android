package org.pimalaya.mail;

import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.database.sqlite.SQLiteStatement;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;

/**
 * The bodies owed whatever their date ({@link MailBodies}), by link id.
 *
 * <p>Delta arrivals (pimdir SYNC section 5), delayed or filed messages, step
 * bodies that did not land. Kept beside the accounts to survive a restart, else
 * an arrival below its frontier stays a summary.
 *
 * <p>One row a key in the app's own database, mail-owed.db (no pimdir table):
 * owing or settling writes the keys it names alone, where the set it replaced
 * was rewritten whole on every change.
 */
final class MailOwed extends SQLiteOpenHelper {
    private static final String DATABASE = "mail-owed.db";
    private static final int VERSION = 1;

    /** Where the set was kept before the table, carried over once. */
    private static final String PREFS = "mail-owed";

    private static final String KEY = "owed";

    private static MailOwed open;

    private final Context context;

    private MailOwed(Context context) {
        super(context, DATABASE, null, VERSION);
        this.context = context;
    }

    /** The application's one helper. */
    private static synchronized SQLiteDatabase db(Context context) {
        Context app = context.getApplicationContext();
        if (open == null || open.context != app) {
            open = new MailOwed(app);
        }
        return open.getWritableDatabase();
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE owed (key TEXT PRIMARY KEY NOT NULL)");
        // NOTE: the set an earlier version kept, carried into the table in
        // the transaction creating it, then forgotten.
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        Set<String> carried = prefs.getStringSet(KEY, Set.of());
        insert(db, carried);
        if (!carried.isEmpty()) {
            prefs.edit().remove(KEY).apply();
        }
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {}

    /** The key one message is owed under. */
    static String key(String collection, String linkId) {
        return collection + "\n" + linkId;
    }

    /** The collection a key names. */
    static String collectionOf(String key) {
        return key.substring(0, key.indexOf('\n'));
    }

    /** The link id a key names. */
    static String linkIdOf(String key) {
        return key.substring(key.indexOf('\n') + 1);
    }

    /** Whether a message's body is owed. */
    static boolean owed(Context context, String collection, String linkId) {
        try (Cursor cursor =
                db(context)
                        .rawQuery(
                                "SELECT 1 FROM owed WHERE key = ?",
                                new String[] {key(collection, linkId)})) {
            return cursor.moveToFirst();
        }
    }

    /** Every key owed, in the order owed. */
    static List<String> all(Context context) {
        List<String> keys = new ArrayList<>();
        try (Cursor cursor = db(context).rawQuery("SELECT key FROM owed ORDER BY rowid", null)) {
            while (cursor.moveToNext()) {
                keys.add(cursor.getString(0));
            }
        }
        return keys;
    }

    /** Owes the bodies of some messages of one collection, by link id. */
    static void owe(Context context, String collection, Collection<String> linkIds) {
        if (linkIds.isEmpty()) {
            return;
        }
        List<String> keys = new ArrayList<>(linkIds.size());
        for (String linkId : linkIds) {
            keys.add(key(collection, linkId));
        }
        SQLiteDatabase db = db(context);
        db.beginTransaction();
        try {
            insert(db, keys);
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    /** Forgets some keys: their bodies landed, or they are owed no more. */
    static void settle(Context context, Collection<String> keys) {
        if (keys.isEmpty()) {
            return;
        }
        SQLiteDatabase db = db(context);
        db.beginTransaction();
        try (SQLiteStatement delete = db.compileStatement("DELETE FROM owed WHERE key = ?")) {
            for (String key : keys) {
                delete.bindString(1, key);
                delete.executeUpdateDelete();
            }
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    /** Adds the keys not owed yet, in the caller's transaction. */
    private static void insert(SQLiteDatabase db, Collection<String> keys) {
        ContentValues row = new ContentValues(1);
        for (String key : keys) {
            row.put("key", key);
            db.insertWithOnConflict("owed", null, row, SQLiteDatabase.CONFLICT_IGNORE);
        }
    }
}
