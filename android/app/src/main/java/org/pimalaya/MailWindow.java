package org.pimalaya;

import android.content.Context;
import android.content.SharedPreferences;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

/**
 * An account's download window: the messages dated on or after its date
 * are the ones the phone holds whole, and the ones the mail list shows.
 *
 * <p>Set at the end of the account's first mail sync ({@link #initial}),
 * moved back only by the user, never forward by itself: new mail lands
 * above it. Never older than the account's bound ({@link MailScope}), since
 * no header is kept below that. A message with no date falls below every
 * window but all mail.
 *
 * <p>App state kept beside the account, as its bound is, and not in the
 * store: it is presentation and download policy, not store truth.
 */
final class MailWindow {
    private static final String PREFS = "mail-window";

    /** What the preference holds for a window of all mail. */
    private static final String ALL = "all";

    private MailWindow() {}

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /**
     * An account's window today, as the RFC 3339 instant a sort key compares
     * against, null for all mail: its date, raised to its bound's floor. An
     * account holding none yet (its first sync owed) reads the first of the
     * month, so nothing downloads past what a first sync would set.
     */
    static String since(Context context, String accountId) {
        String held = prefs(context).getString(accountId, null);
        ZoneId zone = ZoneId.systemDefault();
        String window =
                held == null
                        ? startOf(LocalDate.now(zone).withDayOfMonth(1), zone)
                        : ALL.equals(held) ? null : held;
        return MailScope.clamp(window, MailScope.sinceOf(context, accountId));
    }

    /** Whether an account holds a window yet. */
    static boolean held(Context context, String accountId) {
        return prefs(context).contains(accountId);
    }

    /** Sets an account's window outright, null for all mail: a first sync, a migration. */
    static void set(Context context, String accountId, String date) {
        prefs(context).edit().putString(accountId, date == null ? ALL : date).apply();
    }

    /**
     * Moves an account's window back to {@code date}, null for all mail;
     * a window already older stays where it is.
     */
    static void moveBack(Context context, String accountId, String date) {
        String held = prefs(context).getString(accountId, null);
        if (ALL.equals(held) || (held != null && date != null && date.compareTo(held) >= 0)) {
            return;
        }
        set(context, accountId, date);
    }

    /**
     * Raises a window left below {@code floor}, a narrowed bound's: nothing
     * is kept below it any more.
     */
    static void raise(Context context, String accountId, String floor) {
        String held = prefs(context).getString(accountId, null);
        if (floor != null && held != null && (ALL.equals(held) || held.compareTo(floor) < 0)) {
            set(context, accountId, floor);
        }
    }

    /** Forgets an account's window, with the account. */
    static void forget(Context context, String accountId) {
        prefs(context).edit().remove(accountId).apply();
    }

    /**
     * Gives an account its first window, from what its first mail sync
     * stored ({@link MailStore#firstWindow}); an account set up again keeps
     * the one it holds.
     */
    static void begin(Context context, MailStore store, String accountEmail) {
        String accountId = store.accountIdOf(accountEmail);
        if (!held(context, accountId)) {
            set(context, accountId, store.firstWindow(accountEmail));
        }
    }

    /**
     * Gives an account set up before windows existed its window, once: the
     * floor of its bound where it downloaded bodies ahead (most are held),
     * else its inbox's first chunk as the stored inbox has it, so nothing
     * downloads by surprise. The offline policy it carried is forgotten.
     */
    static void migrate(Context context, MailStore store, String accountEmail) {
        String accountId = store.accountIdOf(accountEmail);
        if (!held(context, accountId)) {
            set(
                    context,
                    accountId,
                    MailOffline.downloadedAhead(context, accountId)
                            ? MailScope.sinceOf(context, accountId)
                            : store.storedWindow(accountEmail));
        }
        MailOffline.forgetPolicy(context, accountId);
    }

    /**
     * The window a first sync leaves, null for all mail: the inbox's floor
     * ({@code inboxFloor}, null where it has none or is not to be used),
     * else the most recent of the mailboxes' floors, else all mail when
     * every mailbox fitted in its first chunk; for an account holding no
     * mail, the first of the month on {@code today} in {@code zone}.
     */
    static String initial(
            boolean empty, String inboxFloor, List<String> floors, LocalDate today, ZoneId zone) {
        if (empty) {
            return startOf(today.withDayOfMonth(1), zone);
        }
        if (inboxFloor != null) {
            return inboxFloor;
        }
        return latest(floors);
    }

    /**
     * The date a list's footer moves the window back to, null for none, the
     * first of a month at midnight in {@code zone} (where the day headers
     * fall): the month of {@code newestBelow}, the newest stored message
     * under the floor, so a month holding nothing is skipped; else, while a
     * shown mailbox still has headers to list below the floor
     * ({@code listing}), the latest such first before it.
     */
    static String next(String newestBelow, String floor, boolean listing, ZoneId zone) {
        if (newestBelow != null && !newestBelow.isEmpty()) {
            return startOf(dayOf(newestBelow, zone).withDayOfMonth(1), zone);
        }
        if (!listing || floor == null) {
            return null;
        }
        LocalDate month = dayOf(floor, zone).withDayOfMonth(1);
        String first = startOf(month, zone);
        return first.compareTo(floor) < 0 ? first : startOf(month.minusMonths(1), zone);
    }

    /** The most recent of some floors, null when none is set. */
    static String latest(Iterable<String> floors) {
        String latest = null;
        for (String floor : floors) {
            if (floor != null && (latest == null || floor.compareTo(latest) > 0)) {
                latest = floor;
            }
        }
        return latest;
    }

    /** Midnight of {@code day} in {@code zone}, as the UTC instant a sort key compares to. */
    static String startOf(LocalDate day, ZoneId zone) {
        return day.atStartOfDay(zone).toInstant().toString();
    }

    /** The day an instant falls on in {@code zone}. */
    static LocalDate dayOf(String instant, ZoneId zone) {
        return Instant.parse(instant).atZone(zone).toLocalDate();
    }
}
