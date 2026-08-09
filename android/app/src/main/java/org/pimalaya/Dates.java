package org.pimalaya;

import android.content.Context;
import android.text.format.DateFormat;

import java.util.Calendar;
import java.util.Date;

/**
 * How the app words a moment: which day it was, how far it is from now,
 * and the exact date when the exact date is what is wanted.
 *
 * <p>One vocabulary for the three domains, because they show the same
 * column: a mail row dates itself, an agenda row says how far off its
 * occurrence is, and a message header says both. Two wordings for one
 * idea would read as two apps.
 *
 * <p><strong>Written here rather than taken from a library</strong>, for
 * the reason the desktop client gives for the same dozen lines: what a
 * reader wants is calendar-relative and what the libraries offer is
 * duration-relative. A message that arrived at 23:00 last night is
 * <em>Yesterday</em>, not nine hours ago, and no amount of configuring a
 * duration formatter produces that.
 */
final class Dates {
    /** How close to now counts as now, in milliseconds. */
    private static final long JUST_NOW = 60 * 1000L;

    /**
     * Which day a moment falls on, as a reader names it: Today,
     * Yesterday, then the distance in the coarsest unit that is still
     * true.
     *
     * <p>Calendar days rather than elapsed hours, which is the whole
     * point: the boundary is midnight, so a message from 23:00 reads
     * Yesterday at 08:00 the next morning, and one from 00:30 reads
     * Today however close to midnight it landed.
     */
    static String day(Context context, long stamp) {
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
     * How far a moment is from now, in either direction: {@code now},
     * {@code in 20 minutes}, {@code 3 days ago}.
     *
     * <p>One unit and never two, because these label a column that is
     * scanned rather than read, and "1 month, 3 days ago" is a sentence.
     */
    static String relative(Context context, long stamp) {
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
     * The same, told in whole days from today rather than in hours from
     * this minute, for a moment that has no time of its own.
     *
     * <p>An all-day entry is on a day: reading a birthday as five hours
     * ago because the day started this morning would be nonsense.
     */
    static String relativeDays(Context context, long stamp) {
        long days = daysBetween(startOfDay(System.currentTimeMillis()), startOfDay(stamp));
        if (days == 0) {
            return context.getString(R.string.date_today);
        }

        String size = spanOfDays(context, Math.abs(days));
        return context.getString(days < 0 ? R.string.date_ago : R.string.date_in, size);
    }

    /** The exact date and time, in the reader's own locale and zone. */
    static String full(Context context, long stamp) {
        if (stamp <= 0) {
            return "";
        }
        Date moment = new Date(stamp);
        return DateFormat.getLongDateFormat(context).format(moment)
                + ", "
                + DateFormat.getTimeFormat(context).format(moment);
    }

    /** The date alone, as the device spells it. */
    static String date(Context context, long stamp) {
        return DateFormat.getMediumDateFormat(context).format(new Date(stamp));
    }

    /**
     * A count of days as the coarsest unit that stays honest: days
     * inside a week, weeks inside a month, then months and years.
     */
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
     * Whole days from one midnight to another, rounded because a day a
     * daylight-saving change made 23 or 25 hours long would otherwise
     * land the count one off.
     */
    private static long daysBetween(long from, long until) {
        return Math.round((double) (until - from) / (24 * 60 * 60 * 1000));
    }

    private Dates() {}
}
