package org.pimalaya;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.database.Cursor;

import io.requery.android.database.sqlite.SQLiteDatabase;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.pimalaya.client.PimalayaException;
import org.pimalaya.client.PimdirSql;
import org.robolectric.RobolectricTestRunner;

import java.util.Map;

/**
 * The canonical pimdir schema, applied to the bundled SQLite.
 *
 * <p>The point of this suite is the seam itself: the app compiles io-pimdir
 * without its {@code client} feature, so the statements come from the crate
 * over JNI while the execution stays on the Java binding. That only works if
 * the SQL the spec writes for rusqlite is accepted verbatim by that driver,
 * which is an assumption worth testing rather than hoping for: {@code STRICT}
 * tables, partial indexes and {@code RETURNING} are all recent SQLite
 * features, which is why the app bundles its SQLite rather than take the
 * device's.
 */
@RunWith(RobolectricTestRunner.class)
public class PimdirSqlTest {
    /** The schema, applied to a fresh in-memory database. */
    private SQLiteDatabase pimdir() {
        SQLiteDatabase db = SQLiteDatabase.create(null);
        for (String statement : PimdirSql.schema()) {
            db.execSQL(statement);
        }
        return db;
    }

    @Test
    public void theBridgeCarriesEveryStatement() {
        Map<String, String> all = PimdirSql.all();

        // The crate indexes sixty; an exact count would break on every spec
        // addition, so assert the shape and the ones this app relies on.
        assertTrue("expected the full statement set, got " + all.size(), all.size() >= 50);
        assertNotNull(all.get("LIST_COLLECTIONS_BY_ACCOUNT"));
        assertNotNull(all.get("LIST_LINK_PLACEMENTS"));
        assertNotNull(all.get("UPSERT_CONTACT_SUMMARY"));
        // The migrations are indexed apart from the statements the profiles
        // run: one is the schema, the others are what a store is queried with.
        assertTrue(PimdirSql.migrations().length >= 1);
        assertEquals(1, PimdirSql.version());
    }

    @Test
    public void theSchemaSplitSurvivesSemicolonsInComments() {
        // The canonical schema documents itself, and one of its comments reads
        // "handing out the next item `seq`; only ever increases". Splitting on
        // ';' alone cuts there and truncates the CREATE TABLE, which SQLite
        // rejects as incomplete input. Every fragment must be a whole statement.
        String[] statements = PimdirSql.schema();

        assertTrue("expected the whole schema, got " + statements.length, statements.length >= 10);
        for (String statement : statements) {
            assertFalse("a comment survived: " + statement, statement.contains("--"));
            if (statement.startsWith("CREATE TABLE")) {
                assertTrue("truncated statement: " + statement, statement.contains(")"));
            }
        }
    }

    @Test
    public void anUnknownStatementFailsLoudly() {
        try {
            PimdirSql.of("NO_SUCH_STATEMENT");
            fail("expected an exception rather than a null statement");
        } catch (PimalayaException expected) {
            assertTrue(expected.getMessage().contains("NO_SUCH_STATEMENT"));
        }
    }

    @Test
    public void androidSqliteAcceptsTheCanonicalSchema() {
        SQLiteDatabase db = pimdir();

        // STRICT tables and the partial indexes are the parts most likely to be
        // refused by an older SQLite, so check they really exist rather than
        // trusting that execSQL did not throw.
        try (Cursor cursor =
                db.rawQuery(
                        "SELECT name FROM sqlite_master WHERE type='table' ORDER BY name", null)) {
            StringBuilder tables = new StringBuilder();
            while (cursor.moveToNext()) {
                tables.append(cursor.getString(0)).append(' ');
            }
            String found = tables.toString();
            assertTrue(found, found.contains("collections"));
            assertTrue(found, found.contains("items"));
            assertTrue(found, found.contains("bindings"));
            assertTrue(found, found.contains("objects"));
            assertTrue(found, found.contains("queue"));
            assertTrue(found, found.contains("sources"));
            assertTrue(found, found.contains("store_meta"));
        }

        try (Cursor cursor =
                db.rawQuery(
                        "SELECT name FROM sqlite_master WHERE type='index' AND name LIKE '%account%'",
                        null)) {
            assertTrue("the partial by-account index is missing", cursor.moveToFirst());
        }
        db.close();
    }

    @Test
    public void theAccountColumnGroupsWithoutPartitioning() {
        SQLiteDatabase db = pimdir();
        db.execSQL(
                "INSERT INTO store_meta(id, version, hash_algo, created_at)"
                        + " VALUES(1, 1, 'blake3', '2026-08-08T00:00:00Z')");

        // Two accounts, one mailbox each, both holding the same message.
        db.execSQL(bind(PimdirSql.of("ENSURE_COLLECTION"), "work/INBOX", "work"));
        db.execSQL(bind(PimdirSql.of("ENSURE_COLLECTION"), "home/INBOX", "home"));
        db.execSQL(
                "INSERT INTO items(collection, link_id, seq, level)"
                        + " VALUES('work/INBOX', '<n@x>', 1, 2)");
        db.execSQL(
                "INSERT INTO items(collection, link_id, seq, level)"
                        + " VALUES('home/INBOX', '<n@x>', 1, 2)");

        // The merged view's filter axis, run as the spec writes it.
        String byAccount = PimdirSql.of("LIST_COLLECTIONS_BY_ACCOUNT").replace(":account", "'work'");
        try (Cursor cursor = db.rawQuery(byAccount, null)) {
            assertTrue(cursor.moveToFirst());
            assertEquals("work/INBOX", cursor.getString(0));
            assertFalse("work owns one collection", cursor.moveToNext());
        }

        // The multiplicity read: one identity, two accounts, one shared seq.
        String placements = PimdirSql.of("LIST_LINK_PLACEMENTS").replace(":link_id", "'<n@x>'");
        try (Cursor cursor = db.rawQuery(placements, null)) {
            assertTrue(cursor.moveToFirst());
            int first = cursor.getInt(2);
            assertTrue(cursor.moveToNext());
            assertEquals("the seq is shared across accounts", first, cursor.getInt(2));
            assertFalse(cursor.moveToNext());
        }
        db.close();
    }

    /**
     * Substitutes a collection and account into a statement, for the reads that
     * take no bind arguments through {@code rawQuery}. Test-only: the store
     * binds parameters properly.
     */
    private static String bind(String sql, String collection, String account) {
        return sql.replace(":collection", "'" + collection + "'")
                .replace(":account", "'" + account + "'");
    }
}
