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
        return ical == null ? null : of(ical.getBytes(StandardCharsets.UTF_8));
    }

    /** What io-pimdir derives from a body's bytes, as {@link #of(String)}. */
    public static Derived of(byte[] ical) {
        if (ical == null) {
            return null;
        }
        try {
            return PimdirSql.derive(PimdirSummary.CALENDAR, ical);
        } catch (RuntimeException underivable) {
            Log.w("pimalaya", "calendar summary not derived", underivable);
            return null;
        }
    }

    /**
     * The key an entry is filed under (STORAGE §9, Annex A.3): the one
     * io-pimdir derives, its UID escaped when spelled like a derived key or
     * the hash of its bytes, else {@code fallback} when nothing derives.
     */
    public static String keyOf(Derived derived, String fallback) {
        return derived == null || derived.linkId() == null ? fallback : derived.linkId();
    }

    /**
     * The key a create of {@code ical} is staged under, as the listing that
     * lands it will name it; {@code uid} when nothing derives.
     */
    public static String keyOf(String ical, String uid) {
        return keyOf(of(ical), uid);
    }
}
