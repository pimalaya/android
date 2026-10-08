package org.pimalaya;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * The staged changes a remote refused for good, by collection and item, so
 * the row they stand on says so rather than waiting for ever.
 *
 * <p>pimdir records no refusal: a rejected push is derived again by the next
 * sync, which is right for a precondition that may hold next time and wrong
 * to show as merely pending when nothing ever will. Kept beside the store
 * rather than in it, as the app's own reading of what a push answered, and
 * cleared once a push of the item is accepted.
 */
final class Refusals {
    private static final String PREFS = "pimalaya.refused";

    private Refusals() {}

    /** Remembers a refusal of one item's change. */
    static void refuse(Context context, String collection, String linkId) {
        prefs(context).edit().putBoolean(key(collection, linkId), true).apply();
    }

    /** Forgets one item's refusal, once a push of it was accepted. */
    static void clear(Context context, String collection, String linkId) {
        SharedPreferences prefs = prefs(context);
        String key = key(collection, linkId);
        if (prefs.contains(key)) {
            prefs.edit().remove(key).apply();
        }
    }

    /** Whether a change of one item was refused for good. */
    static boolean refused(Context context, String collection, String linkId) {
        return prefs(context).getBoolean(key(collection, linkId), false);
    }

    private static String key(String collection, String linkId) {
        return collection + "\n" + linkId;
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
