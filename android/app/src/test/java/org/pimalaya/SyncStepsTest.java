package org.pimalaya;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.res.Resources;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

/**
 * The sync strip names the domain being synced, over one bar filled by an
 * account's collections and the counted steps within each.
 */
@RunWith(RobolectricTestRunner.class)
public class SyncStepsTest {
    private static String line(PimDomain domain) {
        Resources resources = RuntimeEnvironment.getApplication().getResources();
        return resources.getString(SyncSteps.lineOf(domain));
    }

    @Test
    public void eachDomainSaysItsName() {
        assertEquals("Syncing mail", line(PimDomain.MAIL));
        assertEquals("Syncing contacts", line(PimDomain.CONTACTS));
        assertEquals("Syncing calendars", line(PimDomain.CALENDAR));
    }

    @Test
    @Config(qualifiers = "fr")
    public void inFrenchTheAgendaSyncs() {
        assertEquals("Synchronisation des agendas", line(PimDomain.CALENDAR));
    }

    @Test
    public void aBookDownloadFillsItsShareThenTheProjectionTheRest() {
        int download = PimdirEngine.Progress.STAGE_DOWNLOAD;
        int project = PimdirEngine.Progress.STAGE_PROJECT;
        assertEquals(0.3, SyncSteps.shareOf(PimDomain.CONTACTS, download, 115, 230), 1e-9);
        assertEquals(0.6, SyncSteps.shareOf(PimDomain.CONTACTS, download, 230, 230), 1e-9);
        assertEquals(0.6, SyncSteps.shareOf(PimDomain.CALENDAR, project, 0, 23), 1e-9);
        assertEquals(1.0, SyncSteps.shareOf(PimDomain.CALENDAR, project, 23, 23), 1e-9);
    }

    @Test
    public void aMailDownloadFillsItsMailboxWhole() {
        assertEquals(
                1.0,
                SyncSteps.shareOf(PimDomain.MAIL, PimdirEngine.Progress.STAGE_DOWNLOAD, 50, 50),
                1e-9);
    }

    @Test
    public void theBarAddsTheCollectionUnderWayToThoseLanded() {
        // NOTE: two of five calendars landed, the third half way.
        assertEquals(500, SyncSteps.permille(2, 5, 0.5));
        assertEquals(400, SyncSteps.permille(2, 5, 0));
        assertEquals(1000, SyncSteps.permille(5, 5, 0));
    }

    @Test
    public void withNoCollectionCountedTheShareIsTheBar() {
        assertEquals(250, SyncSteps.permille(0, 0, 0.25));
        assertEquals(1000, SyncSteps.permille(0, 0, 2));
    }

    @Test
    public void theBarSpansThePass() {
        // NOTE: contacts (one book) passed, mail (three mailboxes) half way,
        // calendars (two) ahead: 1 + 1.5 of 6.
        assertEquals(416, SyncSteps.across(1, 3, 6, 500));
        assertEquals(1000, SyncSteps.across(4, 2, 6, 1000));
        assertEquals(300, SyncSteps.across(0, 0, 0, 300));
    }

    @Test
    public void aLargeProjectionTellsOncePerPercent() {
        int told = 0;
        for (int done = 1; done <= 1000; done++) {
            if (SyncSteps.tells(done, 1000)) {
                told += 1;
            }
        }
        assertEquals(100, told);
        assertFalse(SyncSteps.tells(9, 1000));
        assertTrue(SyncSteps.tells(10, 1000));
        assertTrue(SyncSteps.tells(1000, 1000));
    }

    @Test
    public void aSmallStepTellsEveryItem() {
        for (int done = 1; done <= 3; done++) {
            assertTrue(SyncSteps.tells(done, 3));
        }
    }

    @Test
    public void nothingCountedIsNeverTold() {
        assertFalse(SyncSteps.tells(0, 10));
        assertFalse(SyncSteps.tells(1, 0));
    }
}
