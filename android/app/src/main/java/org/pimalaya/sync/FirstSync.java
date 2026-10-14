package org.pimalaya.sync;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.List;
import org.pimalaya.account.PimDomain;

/**
 * The first sync each domain of a freshly connected account owes.
 *
 * <p>Paid the first time the domain's tab is reached (mail's on landing), not
 * all behind one dialog; a failed first pass stays owed. Kept in prefs, not the
 * account record: app state, not a secret.
 */
public final class FirstSync {
    private static final String PREFS = "first-sync";

    private FirstSync() {}

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static String key(String email, PimDomain domain) {
        return domain.id + "\n" + email;
    }

    /** Records that a newly connected account owes a domain its first sync. */
    public static void owe(Context context, String email, PimDomain domain) {
        prefs(context).edit().putBoolean(key(email, domain), true).apply();
    }

    /** The accounts owing a domain its first sync, by address. */
    public static List<String> owing(Context context, PimDomain domain) {
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
    public static void paid(Context context, String email, PimDomain domain) {
        prefs(context).edit().remove(key(email, domain)).apply();
    }

    /** Forgets whatever an account owed, with the account. */
    public static void forget(Context context, String email) {
        SharedPreferences.Editor editor = prefs(context).edit();
        for (PimDomain domain : PimDomain.values()) {
            editor.remove(key(email, domain));
        }
        editor.apply();
    }
}
