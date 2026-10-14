package org.pimalaya.sync;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * When each account last synced, which the drawer's account cards say.
 *
 * <p>Per account, not per domain: any pass that came through is a moment the
 * phone and the server agreed. A failed pass leaves the stamp, so the card
 * keeps showing how stale the account is.
 */
public final class SyncStamps {
    private SyncStamps() {}

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences("sync_stamps", Context.MODE_PRIVATE);
    }

    /** Records that the account has just synced. */
    static void mark(Context context, String email) {
        prefs(context).edit().putLong(email, System.currentTimeMillis()).apply();
    }

    /** When the account last synced, 0 when it never has. */
    public static long at(Context context, String email) {
        return prefs(context).getLong(email, 0);
    }
}
