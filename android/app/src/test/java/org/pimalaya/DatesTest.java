package org.pimalaya;

import static org.junit.Assert.assertEquals;

import android.content.Context;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

import java.util.Calendar;

/** The date vocabulary the three lists and the two readers share. */
@RunWith(RobolectricTestRunner.class)
public class DatesTest {
    private final Context context = RuntimeEnvironment.getApplication();

    /** A moment at a given hour, so many days from today. */
    private static long at(int days, int hour) {
        Calendar moment = Calendar.getInstance();
        moment.add(Calendar.DAY_OF_MONTH, days);
        moment.set(Calendar.HOUR_OF_DAY, hour);
        moment.set(Calendar.MINUTE, 0);
        moment.set(Calendar.SECOND, 0);
        moment.set(Calendar.MILLISECOND, 0);
        return moment.getTimeInMillis();
    }

    @Test
    public void theDayBoundaryIsMidnightAndNotElapsedHours() {
        // The whole reason this is not a duration formatter: a message
        // that arrived at 23:00 last night is Yesterday whatever the
        // hour is now, and one from 00:30 is Today however close to
        // midnight it landed.
        assertEquals("Yesterday", Dates.day(context, at(-1, 23)));
        assertEquals("Today", Dates.day(context, at(0, 0)));
        assertEquals("Today", Dates.day(context, at(0, 23)));
    }

    @Test
    public void anOlderDayIsToldInTheCoarsestUnitThatStaysTrue() {
        assertEquals("3 days ago", Dates.day(context, at(-3, 12)));
        assertEquals("2 weeks ago", Dates.day(context, at(-14, 12)));
        assertEquals("2 months ago", Dates.day(context, at(-60, 12)));
        assertEquals("1 year ago", Dates.day(context, at(-400, 12)));
    }

    @Test
    public void aDayStillToComeSaysSoRatherThanCountingBackwards() {
        // Clock skew puts messages in the future; a negative "days ago"
        // would be worse than saying nothing precise.
        assertEquals("Later", Dates.day(context, at(2, 12)));
    }

    @Test
    public void aDistanceIsSignedByItsWording() {
        long now = System.currentTimeMillis();

        // NOTE: a second of slack on every future distance. The count is
        // floored, and the milliseconds this test itself takes would
        // otherwise turn 20 minutes into 19.
        assertEquals("now", Dates.relative(context, now));
        assertEquals("in 20 minutes", Dates.relative(context, now + 20 * 60 * 1000 + 1000));
        assertEquals("1 hour ago", Dates.relative(context, now - 61 * 60 * 1000));
        assertEquals("in 3 days", Dates.relative(context, now + 3 * 24 * 60 * 60 * 1000L + 1000));
    }

    @Test
    public void anAllDayDistanceCountsWholeDaysFromToday() {
        // A birthday is on a day: reading it as five hours ago because
        // the day started this morning would be nonsense.
        assertEquals("Today", Dates.relativeDays(context, at(0, 8)));
        assertEquals("in 1 day", Dates.relativeDays(context, at(1, 0)));
        assertEquals("2 days ago", Dates.relativeDays(context, at(-2, 0)));
    }
}
