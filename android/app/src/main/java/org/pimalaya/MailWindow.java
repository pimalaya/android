package org.pimalaya;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * An account's frontier: the {@code Date} of the oldest message its steps
 * brought ({@link MailStep}), the edge the mail list shows it down to and
 * the body step downloads it from.
 *
 * <p>Set by the account's first step, moved back by each step after it
 * ({@link #frontier}), never forward by a step: new mail lands above it.
 * Moved later only from the account's settings, which frees the bodies
 * below it. Never older than the account's bound ({@link MailScope}).
 * All mail once a step found nothing older left. A message with no date
 * falls below every frontier but all mail.
 *
 * <p>A step over one mailbox alone (a view filtered to it) moves that
 * mailbox's own frontier and never its account's, so the account's other
 * mailboxes do not show days they do not hold; a mailbox reaches down to
 * the older of the two ({@link #since(Context, String, String)}).
 *
 * <p>App state kept beside the account, as its bound is, and not in the
 * store: it is presentation and download policy, not store truth. Beside
 * it, a Gmail account keeps the oldest reception its steps brought
 * ({@link #reach}), where a round over one of its labels lists from, and
 * a JMAP account the ids its last step reached ({@link #anchors}), where
 * the next one starts.
 */
final class MailWindow {
    private static final String PREFS = "mail-window";

    /** Where the own frontiers of mailboxes stepped alone are kept, by collection. */
    private static final String MAILBOXES = "mail-window-mailbox";

    /** What the preference holds for a frontier of all mail. */
    private static final String ALL = "all";

    /** The suffix of the key an account's oldest reception is kept under. */
    private static final String REACH = ":reach";

    /** The suffix of the keys a JMAP account's anchors are kept under. */
    private static final String ANCHORS = ":anchors";

    /**
     * How much older than its reception a message's {@code Date} may be and
     * still move a frontier: past it, an import or a wrong clock.
     */
    private static final long SKEW_MILLIS = 24L * 60 * 60 * 1000;

    private MailWindow() {}

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /**
     * An account's frontier today, as the RFC 3339 instant a sort key
     * compares against, null for all mail: its date, raised to its bound's
     * floor. An account holding none yet (its first step owed) reads the
     * first of the month, so nothing downloads past what a first step would
     * set.
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

    /**
     * A mailbox's frontier today, null for all mail: the older of its own,
     * which steps over it alone moved ({@link #moveBackMailbox}), and its
     * account's, raised to the account's bound.
     */
    static String since(Context context, String accountId, String collection) {
        String account = since(context, accountId);
        String own = mailboxes(context).getString(collection, null);
        if (account == null || own == null) {
            return account;
        }
        String mailbox =
                MailScope.clamp(ALL.equals(own) ? null : own, MailScope.sinceOf(context, accountId));
        return mailbox == null || mailbox.compareTo(account) < 0 ? mailbox : account;
    }

    /**
     * Whether a step over one mailbox alone may bring it more: its account
     * may ({@link #more}), and the mailbox's own frontier, where it holds
     * one, is not all mail and lies above the bound's floor.
     */
    static boolean more(Context context, String accountId, String collection) {
        if (!more(context, accountId)) {
            return false;
        }
        String own = mailboxes(context).getString(collection, null);
        return own == null
                || (!ALL.equals(own) && MailScope.limits(own, MailScope.sinceOf(context, accountId)));
    }

    /**
     * Moves a mailbox's own frontier back to {@code date}, null for all
     * mail: a step over it alone, which leaves its account's alone. A
     * frontier already older stays where it is.
     */
    static void moveBackMailbox(Context context, String collection, String date) {
        String held = mailboxes(context).getString(collection, null);
        if (ALL.equals(held) || (held != null && date != null && date.compareTo(held) >= 0)) {
            return;
        }
        mailboxes(context).edit().putString(collection, date == null ? ALL : date).apply();
    }

    /**
     * Moves the frontier a step left ({@link MailStep.Outcome}): over the
     * account ({@code only} null) its frontier, set by a first step and
     * moved back by the others, all mail once nothing older is left; over
     * one mailbox alone that mailbox's own, the account's left as it is, so
     * its other mailboxes show no day they do not hold.
     */
    static void stepped(
            Context context, String accountId, String only, MailStep.Outcome outcome) {
        if (only != null) {
            if (outcome.exhausted || outcome.frontier != null) {
                moveBackMailbox(context, only, outcome.exhausted ? null : outcome.frontier);
            }
        } else if (outcome.exhausted) {
            set(context, accountId, null);
        } else if (outcome.frontier != null && !held(context, accountId)) {
            set(context, accountId, outcome.frontier);
        } else if (outcome.frontier != null) {
            moveBack(context, accountId, outcome.frontier);
        }
    }

    /**
     * Forgets the own frontiers of an account's mailboxes older than
     * {@code date}, its account's moved later to it: nothing is kept below
     * it any more. All of them for null.
     */
    static void dropMailboxes(Context context, String accountId, String date) {
        String prefix = PimdirAccount.collectionId(accountId, "");
        SharedPreferences.Editor edit = mailboxes(context).edit();
        for (Map.Entry<String, ?> entry : mailboxes(context).getAll().entrySet()) {
            String held = String.valueOf(entry.getValue());
            if (entry.getKey().startsWith(prefix)
                    && (date == null || ALL.equals(held) || held.compareTo(date) < 0)) {
                edit.remove(entry.getKey());
            }
        }
        edit.apply();
    }

    private static SharedPreferences mailboxes(Context context) {
        return context.getSharedPreferences(MAILBOXES, Context.MODE_PRIVATE);
    }

    /** Whether an account holds a frontier yet. */
    static boolean held(Context context, String accountId) {
        return prefs(context).contains(accountId);
    }

    /**
     * Whether a step may bring an account more: its frontier is not all
     * mail and lies above its bound's floor.
     */
    static boolean more(Context context, String accountId) {
        String held = prefs(context).getString(accountId, null);
        return held != null
                && !ALL.equals(held)
                && MailScope.limits(held, MailScope.sinceOf(context, accountId));
    }

    /** Sets an account's frontier outright, null for all mail: a first step, a migration. */
    static void set(Context context, String accountId, String date) {
        prefs(context).edit().putString(accountId, date == null ? ALL : date).apply();
    }

    /**
     * Moves an account's frontier back to {@code date}, null for all mail;
     * a frontier already older stays where it is.
     */
    static void moveBack(Context context, String accountId, String date) {
        String held = prefs(context).getString(accountId, null);
        if (ALL.equals(held) || (held != null && date != null && date.compareTo(held) >= 0)) {
            return;
        }
        set(context, accountId, date);
    }

    /**
     * Raises a frontier left below {@code floor}, a narrowed bound's:
     * nothing is kept below it any more.
     */
    static void raise(Context context, String accountId, String floor) {
        String held = prefs(context).getString(accountId, null);
        if (floor != null && held != null && (ALL.equals(held) || held.compareTo(floor) < 0)) {
            set(context, accountId, floor);
        }
    }

    /**
     * The oldest reception an account's steps brought, in milliseconds
     * since the epoch, null where none recorded one.
     */
    static Long reach(Context context, String accountId) {
        long reach = prefs(context).getLong(accountId + REACH, -1);
        return reach < 0 ? null : reach;
    }

    /** Records a step's oldest reception, keeping the older one held. */
    static void reach(Context context, String accountId, long received) {
        Long held = reach(context, accountId);
        if (held == null || received < held) {
            prefs(context).edit().putLong(accountId + REACH, received).apply();
        }
    }

    /**
     * The ids a JMAP account's last step reached, oldest first, where the
     * next one starts ({@link MailStep}): those of the step over
     * {@code mailbox} (a view filtered to it), else of the account's; empty
     * where no step left any.
     */
    static List<String> anchors(Context context, String accountId, String mailbox) {
        List<String> anchors = new ArrayList<>();
        String held = null;
        if (mailbox != null) {
            held = prefs(context).getString(accountId + ANCHORS + ":" + mailbox, null);
        }
        if (held == null) {
            held = prefs(context).getString(accountId + ANCHORS, null);
        }
        try {
            JSONArray ids = new JSONArray(held == null ? "[]" : held);
            for (int index = 0; index < ids.length(); index++) {
                anchors.add(ids.getString(index));
            }
        } catch (JSONException unreadable) {
            anchors.clear();
        }
        return anchors;
    }

    /**
     * Keeps the anchors a step over {@code mailbox} (null for the account)
     * answered, once its messages landed; none answered keeps those held.
     */
    static void anchor(Context context, String accountId, String mailbox, JSONArray anchors) {
        if (anchors == null || anchors.length() == 0) {
            return;
        }
        String key = accountId + ANCHORS + (mailbox == null ? "" : ":" + mailbox);
        prefs(context).edit().putString(key, anchors.toString()).apply();
    }

    /** Forgets an account's frontier, its reach and its anchors, with the account. */
    static void forget(Context context, String accountId) {
        SharedPreferences.Editor editor =
                prefs(context).edit().remove(accountId).remove(accountId + REACH);
        for (String key : prefs(context).getAll().keySet()) {
            if (key.startsWith(accountId + ANCHORS)) {
                editor.remove(key);
            }
        }
        editor.apply();
        dropMailboxes(context, accountId, null);
    }

    /**
     * The frontier a step's messages leave, null when it brought none: the
     * oldest {@code Date} among them, leaving out a message dated more than
     * a day before its reception (an import, a wrong clock), which is held
     * and shown once the frontier passes it; the oldest of all when every
     * one is such.
     *
     * <p>A step walked {@code byReception} (Gmail, JMAP without
     * {@code sentAt}) left out only messages received before the oldest it
     * brought, whose {@code Date} lies before their reception: its frontier
     * is never older than that reception, so a message received earlier
     * but dated later is never missing from the list above it.
     */
    static String frontier(List<MailStep.Brought> brought, boolean byReception) {
        String oldest = null;
        String oldestTimely = null;
        Long oldestReceived = null;
        for (MailStep.Brought message : brought) {
            if (message.received != null
                    && (oldestReceived == null || message.received < oldestReceived)) {
                oldestReceived = message.received;
            }
            String date = message.date;
            if (date == null || date.isEmpty()) {
                continue;
            }
            if (oldest == null || date.compareTo(oldest) < 0) {
                oldest = date;
            }
            boolean timely =
                    message.received == null
                            || Instant.parse(date).toEpochMilli() >= message.received - SKEW_MILLIS;
            if (timely && (oldestTimely == null || date.compareTo(oldestTimely) < 0)) {
                oldestTimely = date;
            }
        }
        String frontier = oldestTimely != null ? oldestTimely : oldest;
        if (byReception && frontier != null && oldestReceived != null) {
            String reception =
                    Instant.ofEpochMilli(oldestReceived).truncatedTo(ChronoUnit.SECONDS).toString();
            if (reception.compareTo(frontier) > 0) {
                return reception;
            }
        }
        return frontier;
    }

    /**
     * Gives an account set up before windows existed its frontier, once:
     * the floor of its bound where it downloaded bodies ahead (most are
     * held), else its inbox's first chunk as the stored inbox has it, so
     * nothing downloads by surprise. The offline policy it carried is
     * forgotten.
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
     * The frontier first chunks leave, null for all mail: the inbox's floor
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
