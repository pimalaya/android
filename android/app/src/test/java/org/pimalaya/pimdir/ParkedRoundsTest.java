package org.pimalaya.pimdir;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.pimalaya.client.SyncReport;

import java.util.List;

/** The rounds a sync parked, asked about once and accepted for one run. */
public class ParkedRoundsTest {
    private static SyncReport parked(int absent, int bound) {
        return new SyncReport(absent, bound, 0, 0, true, 0, 0, 0, 0, 0, 0);
    }

    private static final SyncReport CLOSED = new SyncReport(0, 0, 0, 0, false, 0, 0, 0, 0, 0, 0);

    @Test
    public void aParkedRoundIsOfferedOnceUntilWhatItFindsChanges() {
        ParkedRounds rounds = new ParkedRounds();
        rounds.synced("acct/Work", parked(30, 40));

        assertEquals(List.of(new ParkedRounds.Parked("acct/Work", 30, 40)), rounds.unoffered());
        assertEquals("asked once", List.of(), rounds.unoffered());

        rounds.synced("acct/Work", parked(30, 40));
        assertEquals("parked again as it was", List.of(), rounds.unoffered());
        rounds.synced("acct/Work", parked(31, 40));
        assertEquals(1, rounds.unoffered().size());

        rounds.synced("acct/Work", CLOSED);
        rounds.synced("acct/Work", parked(31, 40));
        assertEquals("a round closed meanwhile is a new one", 1, rounds.unoffered().size());
    }

    @Test
    public void anAcceptanceHoldsForOneRun() {
        ParkedRounds rounds = new ParkedRounds();
        rounds.synced("acct/Work", parked(30, 40));
        assertFalse(rounds.accepts("acct/Work"));

        rounds.accept(List.of("acct/Work"));
        assertTrue(rounds.accepts("acct/Work"));
        assertFalse(rounds.accepts("acct/Home"));

        rounds.synced("acct/Work", CLOSED);
        assertFalse("spent by the run", rounds.accepts("acct/Work"));
    }
}
