package org.pimalaya;

import android.content.Context;

/**
 * What one sync takes: which accounts, and which of their collections.
 *
 * <p>The drawer's "Sync all" takes every account that is on, whatever the lists
 * hide ({@link #all}); a domain's pull takes what its filter shows, which is
 * never an account that is off ({@link MergedFilter}).
 */
interface SyncScope {
    /** Whether the sync takes the account at all. */
    boolean account(String email);

    /** Whether the sync takes one collection of the account. */
    boolean collection(String email, String collection);

    /** Every collection of every account that is on. */
    static SyncScope all(Context context) {
        return new SyncScope() {
            @Override
            public boolean account(String email) {
                return AccountActivation.enabled(context, email);
            }

            @Override
            public boolean collection(String email, String collection) {
                return account(email);
            }
        };
    }
}
