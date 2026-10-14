package org.pimalaya.mail;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The bodies owed whatever their date ({@link MailBodies}), by link id.
 *
 * <p>Delta arrivals (pimdir SYNC section 5), delayed or filed messages, step
 * bodies that did not land. Kept beside the accounts to survive a restart, else
 * an arrival below its frontier stays a summary.
 */
final class MailOwed {
    private static final String PREFS = "mail-owed";
    private static final String KEY = "owed";

    private MailOwed() {}

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** The key one message is owed under. */
    static String key(String collection, String linkId) {
        return collection + "\n" + linkId;
    }

    /** The collection a key names. */
    static String collectionOf(String key) {
        return key.substring(0, key.indexOf('\n'));
    }

    /** The link id a key names. */
    static String linkIdOf(String key) {
        return key.substring(key.indexOf('\n') + 1);
    }

    /** Whether a message's body is owed. */
    static boolean owed(Context context, String collection, String linkId) {
        return prefs(context).getStringSet(KEY, Set.of()).contains(key(collection, linkId));
    }

    /** Every key owed. */
    static List<String> all(Context context) {
        return new ArrayList<>(prefs(context).getStringSet(KEY, Set.of()));
    }

    /** Owes the bodies of some messages of one collection, by link id. */
    static void owe(Context context, String collection, Collection<String> linkIds) {
        if (linkIds.isEmpty()) {
            return;
        }
        synchronized (MailOwed.class) {
            Set<String> owed = new HashSet<>(prefs(context).getStringSet(KEY, Set.of()));
            for (String linkId : linkIds) {
                owed.add(key(collection, linkId));
            }
            prefs(context).edit().putStringSet(KEY, owed).apply();
        }
    }

    /** Forgets some keys: their bodies landed, or they are owed no more. */
    static void settle(Context context, Collection<String> keys) {
        if (keys.isEmpty()) {
            return;
        }
        synchronized (MailOwed.class) {
            Set<String> owed = new HashSet<>(prefs(context).getStringSet(KEY, Set.of()));
            if (owed.removeAll(keys)) {
                prefs(context).edit().putStringSet(KEY, owed).apply();
            }
        }
    }
}
