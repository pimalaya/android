package org.pimalaya.mail;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * The bodies whose reading killed the app, by {@link MailOwed#key}.
 *
 * <p>A native crash cannot be caught: each read is marked before it starts and
 * cleared after, so a leftover mark is a read the process did not survive.
 * After {@link MailBodies#ATTEMPTS} the body is never read again.
 */
final class MailPoison {
    private static final String PREFS = "mail-poison";

    private MailPoison() {}

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** Marks a read about to start; false, unmarked, for a poisoned body. */
    static boolean begin(Context context, String key) {
        synchronized (MailPoison.class) {
            int died = prefs(context).getInt(key, 0);
            if (died >= MailBodies.ATTEMPTS) {
                return false;
            }
            // NOTE: written before the read starts, or a crash would lose it.
            prefs(context).edit().putInt(key, died + 1).commit();
            return true;
        }
    }

    /** Clears the mark of a read that ended, however it ended. */
    static void end(Context context, String key) {
        synchronized (MailPoison.class) {
            prefs(context).edit().remove(key).apply();
        }
    }

    /** Whether a body is poisoned. */
    static boolean poisoned(Context context, String key) {
        return prefs(context).getInt(key, 0) >= MailBodies.ATTEMPTS;
    }
}
