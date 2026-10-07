package org.pimalaya;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.List;

/**
 * The first sync each domain of a freshly connected account owes, run the
 * first time its tab is reached rather than all three behind one dialog.
 *
 * <p>The connection flow lands on the account's first domain, mail when it
 * has it, and that tab's dialog syncs that domain alone: the newest chunk
 * of every mailbox, the inbox first. Contacts and calendars wait until
 * their tab is opened, each behind its own dialog then. A domain's debt is
 * paid once its first pass went through; one that failed is owed still, and
 * the next visit tries again.
 *
 * <p>Kept beside the account rather than in its record, as its mail bound
 * is: it is app state, not a secret, and only the connection flow sets it.
 */
final class FirstSync {
    private static final String PREFS = "first-sync";

    private FirstSync() {}

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static String key(String email, PimDomain domain) {
        return domain.id + "\n" + email;
    }

    /** Records that a freshly connected account owes a domain its first sync. */
    static void owe(Context context, String email, PimDomain domain) {
        prefs(context).edit().putBoolean(key(email, domain), true).apply();
    }

    /** The accounts owing a domain its first sync, by address. */
    static List<String> owing(Context context, PimDomain domain) {
        List<String> owing = new ArrayList<>();
        String prefix = domain.id + "\n";
        for (String key : prefs(context).getAll().keySet()) {
            if (key.startsWith(prefix)) {
                owing.add(key.substring(prefix.length()));
            }
        }
        return owing;
    }

    /** Records that an account's first sync of a domain went through. */
    static void paid(Context context, String email, PimDomain domain) {
        prefs(context).edit().remove(key(email, domain)).apply();
    }

    /** Forgets whatever an account owed, with the account. */
    static void forget(Context context, String email) {
        SharedPreferences.Editor editor = prefs(context).edit();
        for (PimDomain domain : PimDomain.values()) {
            editor.remove(key(email, domain));
        }
        editor.apply();
    }
}
