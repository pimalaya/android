package org.pimalaya;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.HashSet;
import java.util.Set;

/**
 * Which accounts take part, as the drawer's account settings say.
 *
 * <p>A deactivated account syncs nothing, by any sync, and the lists and their
 * filters leave it out: it stays connected, its data stays stored, and turning
 * it back on brings both back. Kept as the deactivated set, so an account
 * connected later takes part without having to be enrolled.
 */
final class AccountActivation {
    private static final String PREFS = "accounts-deactivated";

    private static final String EMAILS = "emails";

    private AccountActivation() {}

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** Whether the account takes part. */
    static boolean enabled(Context context, String email) {
        return !prefs(context).getStringSet(EMAILS, Set.of()).contains(email);
    }

    /** Turns the account on or off. */
    static void set(Context context, String email, boolean enabled) {
        Set<String> off = new HashSet<>(prefs(context).getStringSet(EMAILS, Set.of()));
        if (enabled) {
            off.remove(email);
        } else {
            off.add(email);
        }
        prefs(context).edit().putStringSet(EMAILS, off).apply();
    }
}
