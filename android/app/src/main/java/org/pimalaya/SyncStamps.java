package org.pimalaya;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * When each account last synced, which the drawer's account cards say.
 *
 * <p>Per account rather than per domain: the card is the account's,
 * and a pass over any of its domains that came through is a moment the
 * phone and the server agreed. A failed pass leaves the stamp where it
 * was, so the card keeps telling how stale the account has grown.
 */
final class SyncStamps {
    private SyncStamps() {}

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences("sync_stamps", Context.MODE_PRIVATE);
    }

    /** Records that the account has just synced. */
    static void mark(Context context, String email) {
        prefs(context).edit().putLong(email, System.currentTimeMillis()).apply();
    }

    /** When the account last synced, 0 when it never has. */
    static long at(Context context, String email) {
        return prefs(context).getLong(email, 0);
    }
}
