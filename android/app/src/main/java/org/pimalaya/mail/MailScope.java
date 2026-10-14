package org.pimalaya.mail;

import android.content.Context;
import android.content.SharedPreferences;

import java.time.LocalDate;
import java.time.ZoneId;

/**
 * An account's mail bound: all of its mail, or the last N months of it.
 *
 * <p>A scope on {@code Date} (pimdir SYNC section 5): rounds list from its
 * floor, undated mail included, a sync deletes nothing outside it, narrowing
 * frees space ({@link MailStore#collectBefore}). In prefs by account id.
 */
public final class MailScope {
    /** The choices the settings offer, in months; 0 is all mail. */
    public static final int[] MONTHS = {0, 1, 3, 6, 12, 24};

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
        return since(months(context, accountId));
    }

    /** One mailbox's floor today: its account's, none for one kept whole. */
    static String sinceOf(Context context, String accountId, String collection) {
        return MailOffline.whole(context, collection) ? null : sinceOf(context, accountId);
    }

    /**
     * What a pass lists a mailbox from: the later of floor and bound, or null.
     *
     * <p>Both are RFC 3339 UTC instants written alike, so they compare as text.
     */
    static String clamp(String floor, String bound) {
        if (floor == null) {
            return bound;
        }
        if (bound == null) {
            return floor;
        }
        return floor.compareTo(bound) >= 0 ? floor : bound;
    }

    /**
     * Whether a mailbox's floor still has kept mail below it.
     *
     * <p>A floor above the bound, or any floor under no bound.
     */
    static boolean limits(String floor, String bound) {
        return floor != null && (bound == null || floor.compareTo(bound) > 0);
    }

    /**
     * An {@code months} bound's floor on {@code today}, null for no bound.
     *
     * <p>Midnight in {@code zone}, first of the month that many months back, an
     * RFC 3339 Annex A instant. A month boundary holds the scope still, so a
     * cut-off round resumes rather than restarts.
     */
    static String since(int months, LocalDate today, ZoneId zone) {
        if (months <= 0) {
            return null;
        }
        return MailWindow.startOf(today.withDayOfMonth(1).minusMonths(months), zone);
    }

    /** The floor of an {@code months} bound today on the device's clock. */
    static String since(int months) {
        ZoneId zone = ZoneId.systemDefault();
        return since(months, LocalDate.now(zone), zone);
    }
}
