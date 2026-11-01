package org.pimalaya.pimdir;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

import java.util.Map;

/**
 * index.db's handle on the platform's classes over SQLite 3.18.2: rollback
 * journal mode with the store attached, and a run that leaves the store's
 * writer free.
 */
@RunWith(RobolectricTestRunner.class)
public class PimdirIndexDbTest {
    private PimdirDb store;
    private PimdirIndexDb index;

    @Before
    public void setUp() {
        store = new PimdirDb(RuntimeEnvironment.getApplication());
        store.getWritableDatabase();
        index = new PimdirIndexDb(store);
    }

    private static String scalar(SQLiteDatabase db, String sql) {
        try (Cursor cursor = PimdirDb.typed(db, sql, new Object[0])) {
            assertTrue(cursor.moveToFirst());
            return cursor.getString(0);
        }
    }

    @Test
    public void indexDbKeepsARollbackJournalWithTheStoreAttached() {
        String mode = index.run(handle -> scalar(handle, "PRAGMA main.journal_mode"));
        assertNotEquals("wal", mode);
        assertEquals(
                "the store, attached, keeps its own",
                "wal",
                index.run(handle -> scalar(handle, "PRAGMA store.journal_mode")));
        assertEquals(
                "pimalaya-android",
                index.run(handle -> scalar(handle, "SELECT shape FROM index_meta")));
    }

    @Test
    public void aRunReadingTheStoreLeavesItsWriterFree() {
        // NOTE: the platform's own transactions below API 35 are immediate or
        // exclusive, and one on index.db would take the attached store's
        // write lock: this write would wait, then fail as locked.
        int wrote =
                index.run(
                        handle -> {
                            scalar(handle, "SELECT count(*) FROM store.items");
                            return PimdirDb.exec(
                                    store.getWritableDatabase(),
                                    "SET_COLLECTION_ROLE",
                                    Map.of("collection", "none", "role", "none"));
                        });
        assertEquals(0, wrote);
    }
}
