package org.pimalaya.mail;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * The name each account's mail goes out under, as its settings say.
 *
 * <p>The display part of the {@code From} header, empty to send the bare
 * address.
 */
public final class SenderName {
    private static final String PREFS = "sender-names";

    private SenderName() {}

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** The account's name, empty when none is set. */
    public static String of(Context context, String email) {
        return prefs(context).getString(email, "");
    }

    /** Sets the account's name, an empty one removing it. */
    public static void set(Context context, String email, String name) {
        SharedPreferences.Editor editor = prefs(context).edit();
        if (name.isEmpty()) {
            editor.remove(email);
        } else {
            editor.putString(email, name);
        }
        editor.apply();
    }
}
