package org.pimalaya.account;

import android.content.Context;

/**
 * What one sync takes: which accounts, and which of their collections.
 *
 * <p>The drawer's "Sync all" takes every account that is on, whatever the lists
 * hide ({@link #all}), of their mailboxes those the mail filter ticks
 * ({@link #mail}); a domain's pull takes what its filter shows, which is
 * never an account that is off ({@link MergedFilter}).
 */
public interface SyncScope {
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

    /**
     * Every account that is on, whatever the list hides, and of its
     * mailboxes those the mail filter ticks: the drawer's mail, since a
     * mailbox unticked neither shows nor syncs.
     */
    static SyncScope mail(Context context) {
        MergedFilter filter = MergedFilter.of(context, PimDomain.MAIL);
        return new SyncScope() {
            @Override
            public boolean account(String email) {
                return AccountActivation.enabled(context, email);
            }

            @Override
            public boolean collection(String email, String collection) {
                return account(email) && filter.chosen(collection);
            }
        };
    }
}
