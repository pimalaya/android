package org.pimalaya;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Which mailboxes are kept whole ("Download this mailbox"): listed past
 * their account's bound and every body downloaded, whatever the account's
 * window ({@link MailWindow}).
 *
 * <p>Kept beside the account rather than inside its encrypted record, as
 * its bound is ({@link MailScope}): it is no secret, and what reads it
 * knows a mailbox by its collection id.
 */
final class MailOffline {
    private static final String PREFS = "mail-offline";

    private MailOffline() {}

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** Whether a mailbox is kept whole. */
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

    /**
     * Whether an account downloaded its bodies ahead under the offline
     * policy windows replaced (in the background, or whole): what its
     * window starts from ({@link MailWindow#migrate}).
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
