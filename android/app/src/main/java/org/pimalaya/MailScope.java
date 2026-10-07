package org.pimalaya;

import android.content.Context;
import android.content.SharedPreferences;

import java.time.LocalDate;
import java.time.ZoneOffset;

/**
 * An account's mail bound: all of its mail, or the last N months of it.
 *
 * <p>The bound is a scope on the {@code Date} header (pimdir SYNC section
 * 5): a round lists the messages dated from its floor on, a message with no
 * usable date included, and nothing outside it is ever deleted by a sync.
 * Narrowing it is what frees the space, through the owner's collection
 * ({@link MailStore#collectBefore}); widening it has the next sync list the
 * band it now lacks.
 *
 * <p>Kept beside the account rather than inside its encrypted record, as the
 * trash is: it is no secret, and the engine reads it by the account id a
 * collection is namespaced under, which is all a driver knows of its
 * account.
 */
final class MailScope {
    /** The choices the settings offer, in months; 0 is all mail. */
    static final int[] MONTHS = {0, 1, 3, 6, 12, 24};

    private static final String PREFS = "mail-scope";

    private MailScope() {}

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** How many months of mail an account keeps, 0 for all of it. */
    static int months(Context context, String accountId) {
        return Math.max(0, prefs(context).getInt(accountId, 0));
    }

    /** Sets how many months of mail an account keeps, 0 for all of it. */
    static void set(Context context, String accountId, int months) {
        prefs(context).edit().putInt(accountId, Math.max(0, months)).apply();
    }

    /** Forgets an account's bound, with the account. */
    static void forget(Context context, String accountId) {
        prefs(context).edit().remove(accountId).apply();
    }

    /** The floor of an account's scope today, null for all mail. */
    static String sinceOf(Context context, String accountId) {
        return since(months(context, accountId), LocalDate.now(ZoneOffset.UTC));
    }

    /**
     * The floor of an {@code months} bound on {@code today}, as the RFC 3339
     * instant Annex A writes a date in: the first day of the month that many
     * months back, at midnight UTC, or null for no bound.
     *
     * <p>A month boundary rather than today's date: the scope then holds
     * still for a month, so a round cut off one day resumes the next under
     * the same scope instead of restarting over one a day narrower, and the
     * coverage is restated once a month rather than once a pass.
     */
    static String since(int months, LocalDate today) {
        if (months <= 0) {
            return null;
        }
        LocalDate floor = today.withDayOfMonth(1).minusMonths(months);
        return floor + "T00:00:00Z";
    }
}
