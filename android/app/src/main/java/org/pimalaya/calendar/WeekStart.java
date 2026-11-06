package org.pimalaya.calendar;

import android.content.Context;
import android.content.SharedPreferences;
import java.util.Calendar;

/** The week's first day: the one picked in the settings, else the locale's. */
public final class WeekStart {
    /** The days the settings offer, in their order there. */
    public static final int[] DAYS = {Calendar.MONDAY, Calendar.SUNDAY};

    private static final String KEY = "first_day";

    private WeekStart() {}

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences("week_start", Context.MODE_PRIVATE);
    }

    /** The week's first day, as a {@link Calendar} day of the week. */
    public static int of(Context context) {
        int picked = prefs(context).getInt(KEY, 0);
        return picked != 0 ? picked : Calendar.getInstance().getFirstDayOfWeek();
    }

    /** Keeps {@code day}, one of {@link #DAYS}, as the week's first. */
    public static void pick(Context context, int day) {
        prefs(context).edit().putInt(KEY, day).apply();
    }
}
