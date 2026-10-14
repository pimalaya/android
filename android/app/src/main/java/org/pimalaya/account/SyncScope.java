package org.pimalaya.account;

import android.content.Context;

/**
 * What one sync takes: which accounts, and which of their collections.
 *
 * <p>"Sync all" takes every account that is on ({@link #all}), with the
 * mailboxes the mail filter ticks ({@link #mail}); a domain's pull takes what
 * its filter shows, never an off account ({@link MergedFilter}).
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
     * Every account that is on, with the mailboxes the mail filter ticks.
     *
     * <p>The drawer's mail: an unticked mailbox neither shows nor syncs.
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
