package org.pimalaya;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.pimalaya.client.Transport;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;

/**
 * The background job's stop is its run's alone (cairn/log
 * 2026-11-18-the-low-lines.md): a service instance the system reuses after
 * a stop starts unstopped, and a stop landing after a run let its waits run
 * again cuts no wait of the next run.
 */
@RunWith(RobolectricTestRunner.class)
public class BackgroundJobTest {
    @After
    public void tearDown() {
        Transport.allowWaits();
    }

    @Test
    public void aReusedJobStartsUnstoppedWithItsWaitsAllowed() throws Exception {
        BackgroundJob job = Robolectric.buildService(BackgroundJob.class).create().get();
        job.onStopJob(null);
        assertTrue(job.stopped());

        job.begin();

        assertFalse("the stop was the last run's", job.stopped());
        try (Transport transport = new Transport()) {
            assertTrue("waits run again", transport.pause(20));
        }
    }
}
