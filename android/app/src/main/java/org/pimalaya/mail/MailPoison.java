package org.pimalaya.mail;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * The bodies whose reading killed the app: a native crash (a stack
 * overflow, an abort) cannot be caught, so a body that brings one down
 * would be downloaded and read again on every run, the app dying each
 * time. Each read is marked before it starts and cleared once it ends,
 * written at once; a mark left over is a read the process did not
 * survive. After {@link MailBodies#ATTEMPTS} of them the body is poisoned:
 * never downloaded or read again, its row saying *Download failed* and its
 * reader that it could not be read. By {@link MailOwed#key}.
 */
public final class MailPoison {
    private static final String PREFS = "mail-poison";

    private MailPoison() {}

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /**
     * Marks a read about to start, false without marking when the body is
     * poisoned and must not be read.
     */
    public static boolean begin(Context context, String key) {
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
    public static void end(Context context, String key) {
        synchronized (MailPoison.class) {
            prefs(context).edit().remove(key).apply();
        }
    }

    /** Whether a body is poisoned. */
    public static boolean poisoned(Context context, String key) {
        return prefs(context).getInt(key, 0) >= MailBodies.ATTEMPTS;
    }
}
