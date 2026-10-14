package org.pimalaya.mail;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.pimalaya.pimdir.PimdirAccount;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * An account's frontier: the oldest {@code Date} its steps brought.
 *
 * <p>The list shows down to it, bodies download from it. Steps only move it
 * back, settings later; never under the bound ({@link MailScope}); undated mail
 * lies below. In prefs, not the store: policy, not store truth.
 */
public final class MailWindow {
    private static final String PREFS = "mail-window";

    /** The own frontiers of mailboxes stepped alone, by collection. */
    private static final String MAILBOXES = "mail-window-mailbox";

    /** What the preference holds for a frontier of all mail. */
    private static final String ALL = "all";

    /** The suffix of the key an account's oldest reception is kept under. */
    private static final String REACH = ":reach";

    /** The suffix of the keys a JMAP account's anchors are kept under. */
    private static final String ANCHORS = ":anchors";

    /**
     * How much older than its reception a {@code Date} still moves a frontier.
     *
     * <p>Past it: an import or a wrong clock.
     */
    private static final long SKEW_MILLIS = 24L * 60 * 60 * 1000;

    private MailWindow() {}

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /**
     * An account's frontier today as an RFC 3339 instant, null for all mail.
     *
     * <p>Raised to the bound's floor. With no step yet, the first of the month,
     * so nothing downloads past what a first step would set.
     */
    public static String since(Context context, String accountId) {
        String held = prefs(context).getString(accountId, null);
        ZoneId zone = ZoneId.systemDefault();
        String window =
                held == null
                        ? startOf(LocalDate.now(zone).withDayOfMonth(1), zone)
                        : ALL.equals(held) ? null : held;
        return MailScope.clamp(window, MailScope.sinceOf(context, accountId));
    }

    /**
     * A mailbox's frontier today, null for all mail; raised to the bound.
     *
     * <p>On Gmail the older of its own ({@link #moveBackMailbox}) and the
     * account's; elsewhere its own once it has one, else the account's.
     */
    public static String since(Context context, String accountId, String collection) {
        String account = since(context, accountId);
        String own = mailboxes(context).getString(collection, null);
        if (own == null) {
            return account;
        }
        String mailbox =
                MailScope.clamp(ALL.equals(own) ? null : own, MailScope.sinceOf(context, accountId));
        if (!MailPolicy.keepsAllMail(context, accountId) || account == null) {
            return mailbox;
        }
        return mailbox == null || mailbox.compareTo(account) < 0 ? mailbox : account;
    }

    /**
     * Whether a step over one mailbox may bring it more.
     *
     * <p>Its own frontier, if any, is not all mail and above the bound's floor;
     * on Gmail, or with none of its own, its account's {@link #more}.
     */
    static boolean more(Context context, String accountId, String collection) {
        String own = mailboxes(context).getString(collection, null);
        boolean mailbox =
                own == null
                        || (!ALL.equals(own)
                                && MailScope.limits(own, MailScope.sinceOf(context, accountId)));
        if (own != null && !MailPolicy.keepsAllMail(context, accountId)) {
            return mailbox;
        }
        return more(context, accountId) && mailbox;
    }

    /**
     * Whether a step over junk or trash alone may bring it more.
     *
     * <p>Its own frontier alone, which no account step moves; never stepped
     * may.
     */
    static boolean moreAlone(Context context, String accountId, String collection) {
        String own = mailboxes(context).getString(collection, null);
        return own == null
                || (!ALL.equals(own) && MailScope.limits(own, MailScope.sinceOf(context, accountId)));
    }

    /**
     * Moves a mailbox's own frontier back to {@code date}, null for all mail.
     *
     * <p>A step over it alone; the account's stays, an older one stays too.
     */
    public static void moveBackMailbox(Context context, String collection, String date) {
        String held = mailboxes(context).getString(collection, null);
        if (ALL.equals(held) || (held != null && date != null && date.compareTo(held) >= 0)) {
            return;
        }
        mailboxes(context).edit().putString(collection, date == null ? ALL : date).apply();
    }

    /**
     * Moves the frontier a step left ({@link MailStep.Outcome}).
     *
     * <p>Over the account ({@code only} null) its own, all mail once nothing
     * older is left; over one mailbox only that mailbox's, so the others show
     * no day they do not hold.
     */
    public static void stepped(
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
     * Forgets mailbox frontiers older than {@code date} (all for null).
     *
     * <p>Its account's moved later to it: nothing is kept below it.
     */
    public static void dropMailboxes(Context context, String accountId, String date) {
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

    /** Whether a step may bring an account more: above the bound, not all. */
    static boolean more(Context context, String accountId) {
        String held = prefs(context).getString(accountId, null);
        return held != null
                && !ALL.equals(held)
                && MailScope.limits(held, MailScope.sinceOf(context, accountId));
    }

    /** Sets an account's frontier outright, null for all mail. */
    public static void set(Context context, String accountId, String date) {
        prefs(context).edit().putString(accountId, date == null ? ALL : date).apply();
    }

    /** Moves an account's frontier back to {@code date}; an older one stays. */
    static void moveBack(Context context, String accountId, String date) {
        String held = prefs(context).getString(accountId, null);
        if (ALL.equals(held) || (held != null && date != null && date.compareTo(held) >= 0)) {
            return;
        }
        set(context, accountId, date);
    }

    /** Raises a frontier below a narrowed bound's {@code floor} to it. */
    static void raise(Context context, String accountId, String floor) {
        String held = prefs(context).getString(accountId, null);
        if (floor != null && held != null && (ALL.equals(held) || held.compareTo(floor) < 0)) {
            set(context, accountId, floor);
        }
    }

    /** The oldest reception an account's steps brought, epoch ms, or null. */
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
     * The ids a JMAP account's last step reached, oldest first.
     *
     * <p>Where the next starts: the step over {@code mailbox}'s, else the
     * account's; empty where no step left any.
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
     * Keeps a landed step's anchors over {@code mailbox} (null for account).
     *
     * <p>None answered keeps those held.
     */
    static void anchor(Context context, String accountId, String mailbox, List<String> anchors) {
        if (anchors == null || anchors.isEmpty()) {
            return;
        }
        String key = accountId + ANCHORS + (mailbox == null ? "" : ":" + mailbox);
        prefs(context).edit().putString(key, new JSONArray(anchors).toString()).apply();
    }

    /** Forgets an account's frontier, reach and anchors. */
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
     * The frontier a step's messages leave, null when it brought none.
     *
     * <p>The oldest date but those dated a day before reception (held till
     * passed), or the oldest of all. {@code byReception} (Gmail, JMAP without
     * {@code sentAt}) never goes below the oldest reception brought.
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
     * Gives an account set up before windows existed its frontier, once.
     *
     * <p>Its bound's floor where it downloaded ahead, else its inbox's first
     * chunk, so nothing downloads by surprise; the old policy is forgotten.
     */
    public static void migrate(Context context, MailStore store, String accountEmail) {
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
     * The frontier first chunks leave, null for all mail.
     *
     * <p>{@code inboxFloor} when set, else the most recent floor, else all mail
     * when each fitted; with no mail, the first of the month in {@code zone}.
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

    /** Midnight of {@code day} in {@code zone}, as a sort key's UTC instant. */
    static String startOf(LocalDate day, ZoneId zone) {
        return day.atStartOfDay(zone).toInstant().toString();
    }

    /** The day an instant falls on in {@code zone}. */
    static LocalDate dayOf(String instant, ZoneId zone) {
        return Instant.parse(instant).atZone(zone).toLocalDate();
    }
}
