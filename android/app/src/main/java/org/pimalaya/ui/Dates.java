package org.pimalaya.ui;

import android.content.Context;
import android.text.format.DateFormat;
import android.text.format.DateUtils;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;
import org.pimalaya.R;

/**
 * How the app words a moment: its day, its distance from now, its date.
 *
 * <p>One vocabulary for the three domains. Hand-written because readers want
 * calendar-relative wording and libraries offer duration-relative: 23:00 last
 * night is Yesterday, not nine hours ago.
 */
public final class Dates {
    /** How close to now counts as now, in milliseconds. */
    private static final long JUST_NOW = 60 * 1000L;

    /**
     * Which day a moment falls on: Today, Yesterday, then the coarsest unit.
     *
     * <p>Calendar days, not elapsed hours: 23:00 reads Yesterday the next
     * morning, 00:30 reads Today however close to midnight.
     */
    public static String day(Context context, long stamp) {
        if (stamp <= 0) {
            return "";
        }

        long days = daysBetween(startOfDay(stamp), startOfDay(System.currentTimeMillis()));
        if (days < 0) {
            return context.getString(R.string.date_later);
        }
        if (days == 0) {
            return context.getString(R.string.date_today);
        }
        if (days == 1) {
            return context.getString(R.string.date_yesterday);
        }
        return context.getString(R.string.date_ago, spanOfDays(context, days));
    }

    /**
     * How far a moment is from now: {@code now}, {@code in 20 minutes}.
     *
     * <p>One unit, never two: the column is scanned, not read.
     */
    public static String relative(Context context, long stamp) {
        long span = stamp - System.currentTimeMillis();
        if (Math.abs(span) < JUST_NOW) {
            return context.getString(R.string.date_now);
        }

        long minutes = Math.abs(span) / 60000;
        String size;
        if (minutes < 60) {
            size = quantity(context, R.plurals.date_minutes, minutes);
        } else if (minutes < 24 * 60) {
            size = quantity(context, R.plurals.date_hours, minutes / 60);
        } else {
            size = spanOfDays(context, minutes / (24 * 60));
        }

        return context.getString(span < 0 ? R.string.date_ago : R.string.date_in, size);
    }

    /**
     * The same in whole days from today, for a moment with no time of its own.
     *
     * <p>An all-day entry is on a day: a birthday is not five hours ago.
     */
    public static String relativeDays(Context context, long stamp) {
        long days = daysBetween(startOfDay(System.currentTimeMillis()), startOfDay(stamp));
        if (days == 0) {
            return context.getString(R.string.date_today);
        }

        String size = spanOfDays(context, Math.abs(days));
        return context.getString(days < 0 ? R.string.date_ago : R.string.date_in, size);
    }

    /** The exact date and time, in the reader's own locale and zone. */
    public static String full(Context context, long stamp) {
        if (stamp <= 0) {
            return "";
        }
        Date moment = new Date(stamp);
        return DateFormat.getLongDateFormat(context).format(moment)
                + ", "
                + DateFormat.getTimeFormat(context).format(moment);
    }

    /** The date alone, as the device spells it. */
    public static String date(Context context, long stamp) {
        return DateFormat.getMediumDateFormat(context).format(new Date(stamp));
    }

    /** The time of day, on the device's 12 or 24 hour clock. */
    public static String time(Context context, long stamp) {
        return DateFormat.getTimeFormat(context).format(new Date(stamp));
    }

    /** The date and the time, the month abbreviated, in the device's zone. */
    public static String dateTime(Context context, long stamp) {
        return DateUtils.formatDateTime(
                context,
                stamp,
                DateUtils.FORMAT_SHOW_DATE
                        | DateUtils.FORMAT_SHOW_TIME
                        | DateUtils.FORMAT_ABBREV_MONTH);
    }

    /** The day and the month in full, without the year: {@code 5 March}. */
    public static String dayMonth(long stamp) {
        return new SimpleDateFormat("d MMMM", Locale.getDefault()).format(new Date(stamp));
    }

    /** The weekday, abbreviated: {@code Thu}. */
    public static CharSequence weekday(long stamp) {
        return DateFormat.format("EEE", new Date(stamp));
    }

    /** The day of the month, without padding: {@code 5}. */
    public static CharSequence dayOfMonth(long stamp) {
        return DateFormat.format("d", new Date(stamp));
    }

    /** The month in full, as a standalone name: {@code March}. */
    public static CharSequence month(long stamp) {
        return DateFormat.format("LLLL", new Date(stamp));
    }

    /** The year, four digits: {@code 2026}. */
    public static CharSequence year(long stamp) {
        return DateFormat.format("yyyy", new Date(stamp));
    }

    /** Days as the coarsest honest unit: days, weeks, then months and years. */
    private static String spanOfDays(Context context, long days) {
        if (days < 7) {
            return quantity(context, R.plurals.date_days, days);
        }
        if (days < 31) {
            return quantity(context, R.plurals.date_weeks, days / 7);
        }
        return days < 365
                ? quantity(context, R.plurals.date_months, days / 30)
                : quantity(context, R.plurals.date_years, days / 365);
    }

    private static String quantity(Context context, int plural, long count) {
        return context.getResources().getQuantityString(plural, (int) count, count);
    }

    /** Midnight opening the day a moment falls in, in the device's zone. */
    private static long startOfDay(long stamp) {
        Calendar moment = Calendar.getInstance();
        moment.setTimeInMillis(stamp);
        moment.set(Calendar.HOUR_OF_DAY, 0);
        moment.set(Calendar.MINUTE, 0);
        moment.set(Calendar.SECOND, 0);
        moment.set(Calendar.MILLISECOND, 0);
        return moment.getTimeInMillis();
    }

    /**
     * Whole days between two midnights, rounded over 23 or 25-hour DST days.
     */
    private static long daysBetween(long from, long until) {
        return Math.round((double) (until - from) / (24 * 60 * 60 * 1000));
    }

    private Dates() {}
}
