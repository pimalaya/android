package org.pimalaya.mail;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The bodies owed whatever their date ({@link MailBodies}): a message a
 * delta listed new to the store (pimdir SYNC section 5), a delayed one or
 * one filed from older mail, and one a step brought whose body did not land.
 * By collection and link id.
 *
 * <p>App state kept beside the accounts, as their frontiers are, so what is
 * owed survives a restart: an arrival dated below its frontier left over a
 * restart would otherwise stay a summary until opened.
 */
public final class MailOwed {
    private static final String PREFS = "mail-owed";
    private static final String KEY = "owed";

    private MailOwed() {}

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** The key one message is owed under. */
    public static String key(String collection, String linkId) {
        return collection + "\n" + linkId;
    }

    /** The collection a key names. */
    public static String collectionOf(String key) {
        return key.substring(0, key.indexOf('\n'));
    }

    /** The link id a key names. */
    public static String linkIdOf(String key) {
        return key.substring(key.indexOf('\n') + 1);
    }

    /** Whether a message's body is owed. */
    static boolean owed(Context context, String collection, String linkId) {
        return prefs(context).getStringSet(KEY, Set.of()).contains(key(collection, linkId));
    }

    /** Every key owed. */
    public static List<String> all(Context context) {
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
    public static void settle(Context context, Collection<String> keys) {
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
