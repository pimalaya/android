package org.pimalaya;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * How much of an account's mail is kept to read offline: which bodies
 * download without being opened, on which networks, and which mailboxes
 * are kept whole.
 *
 * <p>Kept beside the account rather than inside its encrypted record, as
 * its bound is ({@link MailScope}): it is no secret, and what reads it
 * knows an account by the id its collections are namespaced under.
 *
 * <p>The bound is the only limit: no storage cap. Space is freed from
 * Deleted items and by narrowing the bound.
 */
final class MailOffline {
    /** Which bodies an account downloads before anyone opens them. */
    enum Policy {
        /** None: a body is fetched when its message is opened. */
        ON_OPEN,
        /** Every listed message's within the bound, after each pass and fill step. */
        BACKGROUND,
        /** The bound set to all mail, and every body: a full copy. */
        WHOLE
    }

    private static final String PREFS = "mail-offline";

    private MailOffline() {}

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** An account's policy, bodies on open unless set. */
    static Policy policy(Context context, String accountId) {
        int ordinal = prefs(context).getInt("policy:" + accountId, 0);
        Policy[] policies = Policy.values();
        return ordinal >= 0 && ordinal < policies.length ? policies[ordinal] : Policy.ON_OPEN;
    }

    static void setPolicy(Context context, String accountId, Policy policy) {
        prefs(context).edit().putInt("policy:" + accountId, policy.ordinal()).apply();
    }

    /** Whether an account's bodies also download on a metered network; off unless set. */
    static boolean metered(Context context, String accountId) {
        return prefs(context).getBoolean("metered:" + accountId, false);
    }

    static void setMetered(Context context, String accountId, boolean metered) {
        prefs(context).edit().putBoolean("metered:" + accountId, metered).apply();
    }

    /**
     * Whether a mailbox is kept whole ("Download this mailbox"): listed
     * past its account's bound and every body downloaded, whatever the
     * account's policy.
     */
    static boolean whole(Context context, String collection) {
        return prefs(context).getBoolean("whole:" + collection, false);
    }

    static void setWhole(Context context, String collection, boolean whole) {
        SharedPreferences.Editor editor = prefs(context).edit();
        if (whole) {
            editor.putBoolean("whole:" + collection, true);
        } else {
            editor.remove("whole:" + collection);
        }
        editor.apply();
    }

    /** Whether a mailbox's bodies download without being opened. */
    static boolean downloads(Context context, String accountId, String collection) {
        return policy(context, accountId) != Policy.ON_OPEN || whole(context, collection);
    }

    /** Whether any mailbox of an account downloads its bodies. */
    static boolean downloadsAny(Context context, String accountId) {
        if (policy(context, accountId) != Policy.ON_OPEN) {
            return true;
        }
        String prefix = "whole:" + PimdirAccount.collectionId(accountId, "");
        for (String key : prefs(context).getAll().keySet()) {
            if (key.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    /** Forgets an account's settings and its mailboxes', with the account. */
    static void forget(Context context, String accountId) {
        SharedPreferences.Editor editor = prefs(context).edit();
        editor.remove("policy:" + accountId);
        editor.remove("metered:" + accountId);
        String prefix = "whole:" + PimdirAccount.collectionId(accountId, "");
        for (String key : prefs(context).getAll().keySet()) {
            if (key.startsWith(prefix)) {
                editor.remove(key);
            }
        }
        editor.apply();
    }
}
