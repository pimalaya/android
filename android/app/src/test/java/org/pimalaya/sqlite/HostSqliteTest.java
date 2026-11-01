package org.pimalaya.sqlite;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteException;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

/**
 * The SQLite the tests run on, through the platform's classes: Android 8.0's,
 * the oldest the app runs on, so a statement or a pragma it lacks fails here
 * before a device.
 */
@RunWith(RobolectricTestRunner.class)
public class HostSqliteTest {
    @Test
    public void theTestsRunOnAndroid8sSqlite() {
        SQLiteDatabase db = SQLiteDatabase.create(null);
        try {
            assertEquals("3.18.2", text(db, "SELECT sqlite_version()"));
            // NOTE: Android compiles SQLITE_SECURE_DELETE, and FTS4 in.
            assertEquals("1", text(db, "PRAGMA secure_delete"));
            db.execSQL("CREATE VIRTUAL TABLE words USING fts4(word)");
            for (String absent :
                    new String[] {
                        "SELECT json('{}')",
                        "CREATE VIRTUAL TABLE five USING fts5(word)",
                        "CREATE TABLE strict(id INTEGER) STRICT",
                    }) {
                try {
                    db.execSQL(absent);
                    fail("Android 8.0's SQLite runs: " + absent);
                } catch (SQLiteException refused) {
                    // NOTE: what the floor lacks.
                }
            }
        } finally {
            db.close();
        }
    }

    /** {@code PRAGMA trusted_schema} is 3.31's: 3.18 answers no row. */
    @Test
    public void theFloorHasNoTrustedSchema() {
        SQLiteDatabase db = SQLiteDatabase.create(null);
        try (Cursor cursor = db.rawQuery("PRAGMA trusted_schema", null)) {
            assertTrue(!cursor.moveToFirst());
        } finally {
            db.close();
        }
    }

    private static String text(SQLiteDatabase db, String sql) {
        try (Cursor cursor = db.rawQuery(sql, null)) {
            assertTrue(cursor.moveToFirst());
            return cursor.getString(0);
        }
    }
}
