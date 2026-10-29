package org.pimalaya.calendar;

import android.util.Log;

import org.pimalaya.client.Derived;
import org.pimalaya.client.PimdirSql;
import org.pimalaya.pimdir.PimdirSummary;

import java.nio.charset.StandardCharsets;

/**
 * The summary and sort key a calendar body derives (pimdir STORAGE Annex A.3
 * to A.5), through io-pimdir.
 *
 * <p>What every writer of a calendar item hands the store with the body, so
 * {@code task_summary} and the other rows say what the body says: a listing
 * from a server or the phone, and a staged edit (cairn/changes/gtd-hardening).
 * The agenda still expands at render time; the rows are for the readers that
 * list items without a body, the Tasks tab first.
 */
public final class CalendarSummaries {
    private CalendarSummaries() {}

    /**
     * What io-pimdir derives from one calendar body; null when it derives
     * nothing, the caller then writing none, which keeps the stored row.
     */
    public static Derived of(String ical) {
        if (ical == null) {
            return null;
        }
        try {
            return PimdirSql.derive(PimdirSummary.CALENDAR, ical.getBytes(StandardCharsets.UTF_8));
        } catch (RuntimeException underivable) {
            Log.w("pimalaya", "calendar summary not derived", underivable);
            return null;
        }
    }
}
