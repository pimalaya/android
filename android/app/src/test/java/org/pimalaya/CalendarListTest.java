package org.pimalaya;

import static org.junit.Assert.assertEquals;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

/**
 * The agenda row's arithmetic on civil stamps.
 *
 * <p>Worth pinning because the stamps are strings the bridge produced on
 * wall-clock time: reading one wrong shifts a row by a month or reports
 * a length nobody can check against the calendar it came from.
 */
@RunWith(RobolectricTestRunner.class)
public class CalendarListTest {
    @Test
    public void aLengthIsTheSecondsBetweenTwoCivilStamps() {
        assertEquals(3600, CalendarList.secondsBetween("20260105T090000", "20260105T100000"));
        assertEquals(
                "one crossing midnight is still its own length",
                5400,
                CalendarList.secondsBetween("20260105T233000", "20260106T010000"));
    }

    @Test
    public void aMomentHasNoLengthAndNothingHasANegativeOne() {
        assertEquals(0, CalendarList.secondsBetween("20260105T090000", "20260105T090000"));
        // An end before its start is a broken object, not a row that
        // counts backwards: every journal entry ends where it starts, so
        // the floor is what keeps a bad one from printing a negative.
        assertEquals(0, CalendarList.secondsBetween("20260105T100000", "20260105T090000"));
    }

    @Test
    public void aDateOnlyStampIsReadAtMidnight() {
        // The bridge widens an all-day DTSTART to T000000, but a stamp
        // arriving as the bare date must still land on the same day
        // rather than on whatever time the calendar was constructed at.
        assertEquals(
                CalendarList.stampOf("20260105T000000"), CalendarList.stampOf("20260105"));
    }
}
