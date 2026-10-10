package org.pimalaya;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.res.Resources;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

/**
 * The sync dialog's detail line counts what the domain being synced holds:
 * events while the agenda syncs, messages while the mail does, contacts
 * while the books do; the phone steps are the two mirrors'.
 */
@RunWith(RobolectricTestRunner.class)
public class SyncStepsTest {
    private static String line(PimDomain domain, int stage, int count) {
        Resources resources = RuntimeEnvironment.getApplication().getResources();
        return SyncSteps.text(resources, domain, stage, count);
    }

    @Test
    public void theAgendaDownloadsEvents() {
        // NOTE: the owner's device read "Downloading 23 contact(s)" while
        // the agenda synced.
        assertEquals(
                "Downloading 23 events",
                line(PimDomain.CALENDAR, PimdirEngine.Progress.STAGE_DOWNLOAD, 23));
        assertEquals(
                "Downloading 1 event",
                line(PimDomain.CALENDAR, PimdirEngine.Progress.STAGE_DOWNLOAD, 1));
    }

    @Test
    public void mailDownloadsMessages() {
        assertEquals(
                "Downloading 50 messages",
                line(PimDomain.MAIL, PimdirEngine.Progress.STAGE_DOWNLOAD, 50));
    }

    @Test
    public void booksDownloadContacts() {
        assertEquals(
                "Downloading 2 contacts",
                line(PimDomain.CONTACTS, PimdirEngine.Progress.STAGE_DOWNLOAD, 2));
        assertEquals(
                "Writing 1 contact to the phone",
                line(PimDomain.CONTACTS, PimdirEngine.Progress.STAGE_PROJECT, 1));
        assertEquals(
                "Reconciling with the phone contacts",
                line(PimDomain.CONTACTS, PimdirEngine.Progress.STAGE_PHONE, 0));
    }

    @Test
    public void theAgendaReconcilesWithThePhoneCalendar() {
        assertEquals(
                "Reconciling with the phone calendar",
                line(PimDomain.CALENDAR, PimdirEngine.Progress.STAGE_PHONE, 0));
        assertEquals(
                "Writing 3 events to the phone",
                line(PimDomain.CALENDAR, PimdirEngine.Progress.STAGE_PROJECT, 3));
    }

    @Test
    public void mailHasNoPhoneSteps() {
        assertNull(line(PimDomain.MAIL, PimdirEngine.Progress.STAGE_PHONE, 0));
        assertNull(line(PimDomain.MAIL, PimdirEngine.Progress.STAGE_PROJECT, 3));
    }

    @Test
    public void everyDomainSharesTheNeutralSteps() {
        for (PimDomain domain : PimDomain.values()) {
            assertEquals(
                    "Checking the server for changes",
                    line(domain, PimdirEngine.Progress.STAGE_SERVER, 0));
            assertEquals(
                    "Sending 3 changes to the server",
                    line(domain, PimdirEngine.Progress.STAGE_UPLOAD, 3));
            assertEquals(
                    "Resolving 1 conflict",
                    line(domain, PimdirEngine.Progress.STAGE_RESOLVE, 1));
        }
    }

    @Test
    public void eachDomainCountsItsCollections() {
        Resources resources = RuntimeEnvironment.getApplication().getResources();
        assertEquals(
                "Address books synced: 1 of 3",
                resources.getString(SyncSteps.collectionsOf(PimDomain.CONTACTS), 1, 3));
        assertEquals(
                "Mailboxes synced: 3 of 12",
                resources.getString(SyncSteps.collectionsOf(PimDomain.MAIL), 3, 12));
        assertEquals(
                "Calendars synced: 2 of 5",
                resources.getString(SyncSteps.collectionsOf(PimDomain.CALENDAR), 2, 5));
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

    @Test
    @Config(qualifiers = "fr")
    public void inFrenchTheAgendaDownloadsEvents() {
        assertEquals(
                "Téléchargement de 5 événements",
                line(PimDomain.CALENDAR, PimdirEngine.Progress.STAGE_DOWNLOAD, 5));
        assertEquals(
                "Téléchargement de 1 message",
                line(PimDomain.MAIL, PimdirEngine.Progress.STAGE_DOWNLOAD, 1));
    }
}
