package org.pimalaya.mail;

import android.content.Context;
import android.content.SharedPreferences;
import org.pimalaya.pimdir.PimdirAccount;

/**
 * Which mailboxes are kept whole ("Download this mailbox").
 *
 * <p>Listed past the account's bound with every body, whatever its window
 * ({@link MailWindow}). Kept in prefs like the bound ({@link MailScope}): no
 * secret, and read by collection id.
 */
public final class MailOffline {
    private static final String PREFS = "mail-offline";

    private MailOffline() {}

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** Whether a mailbox is kept whole. */
    public static boolean whole(Context context, String collection) {
        return prefs(context).getBoolean("whole:" + collection, false);
    }

    /** Keeps a mailbox whole, or stops keeping it. */
    public static void setWhole(Context context, String collection, boolean whole) {
        SharedPreferences.Editor editor = prefs(context).edit();
        if (whole) {
            editor.putBoolean("whole:" + collection, true);
        } else {
            editor.remove("whole:" + collection);
        }
        editor.apply();
    }

    /**
     * Whether an account downloaded bodies ahead under the old offline policy.
     *
     * <p>What its window starts from ({@link MailWindow#migrate}).
     */
    static boolean downloadedAhead(Context context, String accountId) {
        return prefs(context).getInt("policy:" + accountId, 0) != 0;
    }

    /** Forgets the offline policy and metered setting windows replaced. */
    static void forgetPolicy(Context context, String accountId) {
        prefs(context)
                .edit()
                .remove("policy:" + accountId)
                .remove("metered:" + accountId)
                .apply();
    }

    /** Forgets an account's mailboxes kept whole, with the account. */
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
