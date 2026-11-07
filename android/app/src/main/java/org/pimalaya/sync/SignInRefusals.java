package org.pimalaya.sync;

import android.content.Context;
import android.content.SharedPreferences;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.pimalaya.pimdir.PimdirEngine;

/** The accounts whose sync was refused for its sign-in, by place. */
public final class SignInRefusals {
    /** Sending's place; the domains' are their ids. */
    public static final String SENDING = "sending";

    private SignInRefusals() {}

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences("sign_in_refused", Context.MODE_PRIVATE);
    }

    /** Whether a failure is a sign-in refused, a 401 past any renewal. */
    public static boolean refusal(Exception error) {
        return PimdirEngine.expiredToken(error);
    }

    /** Notes a place's sign-in: none clears it, a refusal records it. */
    static void note(Context context, String email, String place, Exception failure) {
        Set<String> places = prefs(context).getStringSet(email, Set.of());
        Set<String> noted = new HashSet<>(places);
        if (failure == null) {
            noted.remove(place);
        } else if (refusal(failure)) {
            noted.add(place);
        }
        if (noted.equals(places)) {
            return;
        }
        SharedPreferences.Editor editor = prefs(context).edit();
        (noted.isEmpty() ? editor.remove(email) : editor.putStringSet(email, noted)).apply();
    }

    /** Whether the account's sync was refused for its sign-in. */
    public static boolean refused(Context context, String email) {
        return !prefs(context).getStringSet(email, Set.of()).isEmpty();
    }

    /** The accounts refused for their sign-in. */
    public static List<String> accounts(Context context) {
        return new ArrayList<>(prefs(context).getAll().keySet());
    }

    /** Forgets an account's refusals, signed in again or removed. */
    public static void forget(Context context, String email) {
        prefs(context).edit().remove(email).apply();
    }
}
