package org.pimalaya;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Locale;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.pimalaya.client.EventTime;
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
    /** A floating time, read on the device's clock. */
    private static EventTime local(String time) {
        return new EventTime(time, EventTime.FLOATING, "", null);
    }

    @Test
    public void aLengthIsTheSecondsBetweenTwoTimes() {
        assertEquals(
                3600,
                CalendarList.secondsBetween(local("20260105T090000"), local("20260105T100000")));
        assertEquals(
                "one crossing midnight is still its own length",
                5400,
                CalendarList.secondsBetween(local("20260105T233000"), local("20260106T010000")));
        // A flight leaving New York at 18:00 and landing in Paris at 07:30
        // the next morning runs seven and a half hours, not thirteen.
        assertEquals(
                27000,
                CalendarList.secondsBetween(
                        new EventTime("20260105T180000", EventTime.ZONED, "America/New_York", null),
                        new EventTime("20260106T073000", EventTime.ZONED, "Europe/Paris", null)));
    }

    @Test
    public void aMomentHasNoLengthAndNothingHasANegativeOne() {
        assertEquals(
                0, CalendarList.secondsBetween(local("20260105T090000"), local("20260105T090000")));
        // An end before its start is a broken object, not a row that
        // counts backwards: every journal entry ends where it starts, so
        // the floor is what keeps a bad one from printing a negative.
        assertEquals(
                0, CalendarList.secondsBetween(local("20260105T100000"), local("20260105T090000")));
    }

    @Test
    public void aDayIsReadAtItsMidnight() {
        assertEquals(
                Zones.instant(local("20260105T000000")), CalendarList.stampOf("20260105"));
    }

    @Test
    public void aWeekStartsOnTheLocalesFirstDay() {
        Locale before = Locale.getDefault();
        try {
            // Wednesday 7 October 2026.
            Locale.setDefault(Locale.FRANCE);
            assertEquals("20261005", CalendarList.firstOfWeek("20261007"));
            Locale.setDefault(Locale.US);
            assertEquals("20261004", CalendarList.firstOfWeek("20261007"));
        } finally {
            Locale.setDefault(before);
        }
    }

    @Test
    public void withNoDayPickedAWeekListsItsSevenDaysAlone() {
        // This week, today being Wednesday 7 October 2026: Monday and
        // Tuesday stay listed, and nothing past Sunday is.
        String week = "20261005";
        assertFalse(CalendarList.kept("20261004", null, week));
        assertTrue(CalendarList.kept("20261005", null, week));
        assertTrue(CalendarList.kept("20261006", null, week));
        assertTrue(CalendarList.kept("20261007", null, week));
        assertTrue(CalendarList.kept("20261011", null, week));
        assertFalse(CalendarList.kept("20261012", null, week));
        assertFalse(CalendarList.kept("20261120", null, week));

        // Any other week the same way, across a month's end.
        String other = "20261026";
        assertFalse(CalendarList.kept("20261025", null, other));
        assertTrue(CalendarList.kept("20261026", null, other));
        assertTrue(CalendarList.kept("20261101", null, other));
        assertFalse(CalendarList.kept("20261102", null, other));
    }

    @Test
    public void aPickedDayListsItselfAlone() {
        String week = "20261005";
        assertTrue(CalendarList.kept("20261009", "20261009", week));
        assertFalse(CalendarList.kept("20261007", "20261009", week));
        assertFalse(CalendarList.kept("20261010", "20261009", week));
    }
}
