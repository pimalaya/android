package org.pimalaya;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.database.sqlite.SQLiteDatabase;
import java.io.File;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

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

    @Test
    public void noStoreYetOpensAFreshOne() {
        App app = (App) RuntimeEnvironment.getApplication();

        assertFalse(app.storeRefused());
        assertTrue(app.pimdir().getReadableDatabase().isOpen());
    }
}
