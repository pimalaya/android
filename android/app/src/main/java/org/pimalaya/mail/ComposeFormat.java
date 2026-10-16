package org.pimalaya.mail;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Whether each account's new messages start rich, as its settings say.
 *
 * <p>Rich by default: a rich message with no formatting goes out as its text
 * alone, so the default costs a plain writer nothing.
 */
public final class ComposeFormat {
    private static final String PREFS = "compose-format";

    private ComposeFormat() {}

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** Whether the account's new messages start rich. */
    public static boolean rich(Context context, String email) {
        return prefs(context).getBoolean(email, true);
    }

    /** Sets whether they do; rich, the default, removes the entry. */
    public static void set(Context context, String email, boolean rich) {
        SharedPreferences.Editor editor = prefs(context).edit();
        if (rich) {
            editor.remove(email);
        } else {
            editor.putBoolean(email, false);
        }
        editor.apply();
    }
}
