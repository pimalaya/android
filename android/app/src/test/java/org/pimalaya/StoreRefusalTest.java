package org.pimalaya;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.database.sqlite.SQLiteDatabase;
import android.widget.TextView;
import java.io.File;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.pimalaya.sqlite.ShadowHostSqliteConnection;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.shadows.ShadowSQLiteConnection;

/**
 * The start-up block: a store of another draft is neither read nor written
 * (pimdir STORAGE §6), the app standing aside for the reinstall screen.
 */
@RunWith(RobolectricTestRunner.class)
public class StoreRefusalTest {
    @Test
    public void aStoreOfAnEarlierDraftBlocksTheStore() {
        App app = (App) RuntimeEnvironment.getApplication();
        File dir = new File(app.getFilesDir(), "pimdir");
        assertTrue(dir.isDirectory() || dir.mkdirs());
        SQLiteDatabase old = SQLiteDatabase.openOrCreateDatabase(new File(dir, "pimdir.db"), null);
        old.execSQL(
                "CREATE TABLE store_meta (id INTEGER PRIMARY KEY, format TEXT NOT NULL,"
                        + " version INTEGER NOT NULL, hash_algo TEXT NOT NULL)");
        old.execSQL("INSERT INTO store_meta VALUES(1, 'pimdir', 1, 'sha256-128')");
        old.setVersion(1);
        old.close();

        assertTrue(app.storeRefused());
        assertEquals("store-version", app.refusal().optString("refused"));
        try {
            app.pimdir();
            fail("a refused store opened");
        } catch (IllegalStateException refused) {
            // NOTE: no entry point opens it.
        }
    }

    /**
     * A phone whose SQLite never syncs the writer at every commit (STORAGE
     * §4.1) stands the app aside on the refusal screen, which says so,
     * rather than crashing at the first write.
     */
    @Test
    public void aWriterNeverSyncingAtFullBlocksTheStoreReadably() {
        ShadowSQLiteConnection.setDefaultSyncMode("NORMAL");
        ShadowHostSqliteConnection.ignoreSynchronousFull(Integer.MAX_VALUE);
        App app = (App) RuntimeEnvironment.getApplication();
        try {
            assertTrue(app.storeRefused());
            assertEquals(App.STORE_SYNC, app.refusal().optString("refused"));
            try {
                app.pimdir();
                fail("a refused store opened");
            } catch (IllegalStateException refused) {
                assertTrue(refused.getMessage(), refused.getMessage().contains("store-sync"));
            }

            StoreRefusedActivity screen =
                    Robolectric.buildActivity(StoreRefusedActivity.class).setup().get();
            TextView title = screen.findViewById(R.id.store_refused_title);
            assertEquals(
                    app.getString(R.string.store_sync_refused_title),
                    title.getText().toString());
        } finally {
            ShadowHostSqliteConnection.ignoreSynchronousFull(0);
        }
    }

    @Test
    public void noStoreYetOpensAFreshOne() {
        App app = (App) RuntimeEnvironment.getApplication();

        assertFalse(app.storeRefused());
        assertTrue(app.pimdir().getReadableDatabase().isOpen());
    }
}
