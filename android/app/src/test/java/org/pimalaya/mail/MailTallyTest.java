package org.pimalaya.mail;

import static org.junit.Assert.assertEquals;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * A step or body run counts its bodies as their files land, out of its plan.
 *
 * <p>A step closes its plan on what it brought; a run settles chunk by chunk,
 * adding a replan to what it landed.
 */
public class MailTallyTest {
    @Rule public TemporaryFolder folder = new TemporaryFolder();

    /** Every count told, as {landed, planned}. */
    private final List<int[]> told = Collections.synchronizedList(new ArrayList<>());

    private final MailTally tally =
            new MailTally((landed, planned) -> told.add(new int[] {landed, planned}));

    private int[] last() {
        return told.get(told.size() - 1);
    }

    @Test
    public void aStepFillsOutOfItsCountThenClosesOnWhatItBrought() {
        tally.expect(MailStep.COUNT);
        assertEquals(0, last()[0]);
        assertEquals(50, last()[1]);

        tally.land(10);
        tally.land(10);
        assertEquals(20, last()[0]);
        assertEquals(50, last()[1]);

        // NOTE: the account held 23 more, the walk ran out.
        tally.land(3);
        tally.expect(0);
        assertEquals(23, last()[0]);
        assertEquals(23, last()[1]);
    }

    @Test
    public void aCountNeverPassesItsPlan() {
        tally.expect(2);
        tally.land(5);
        assertEquals(2, last()[0]);
        assertEquals(2, last()[1]);
    }

    @Test
    public void aRunSettlesItsChunksAndAddsAReplanToWhatItLanded() {
        tally.expect(100);
        tally.land(20);
        // NOTE: the chunk took 24, four of them linked without a file.
        tally.settle(24);
        assertEquals(24, last()[0]);
        assertEquals(100, last()[1]);

        tally.expect(30);
        assertEquals(24, last()[0]);
        assertEquals(54, last()[1]);

        tally.reset();
        tally.expect(0);
        assertEquals(0, last()[0]);
        assertEquals(0, last()[1]);
    }

    @Test
    public void aWatchCountsTheFilesABridgeCallWrites() throws IOException {
        File dir = folder.newFolder();
        Files.write(new File(dir, "before").toPath(), new byte[] {1});
        tally.expect(10);

        try (MailTally.Watch watch = tally.watch(dir)) {
            Files.write(new File(dir, "m1").toPath(), new byte[] {1});
            Files.write(new File(dir, "m2").toPath(), new byte[] {1});
            watch.poll();
            assertEquals("a file already there not counted", 2, last()[0]);
            Files.write(new File(dir, "m3").toPath(), new byte[] {1});
        }

        assertEquals("the last ones counted as it closes", 3, last()[0]);
        assertEquals(3, tally.landed());
    }
}
