package org.pimalaya;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.Collection;
import java.util.HashSet;
import java.util.Set;

/**
 * One domain's filter: which accounts and which of their collections its list
 * shows, keyed by collection id and kept across restarts (the filter page,
 * {@link FilterPage}).
 *
 * <p>One per domain, since the collections are the domain's own. State is kept
 * as the <em>hidden</em> sets rather than the shown ones, so the empty filter
 * is the everything-shown default (which is also where a build that kept the
 * filter in memory starts from) and a collection listed later shows up without
 * having to be enrolled.
 *
 * <p>An account is hidden apart from its collections, so hiding it keeps
 * which of them were hidden for when it comes back, the page folding them
 * away meanwhile. An account that is off
 * ({@link AccountActivation}) is never shown, whatever the filter says.
 *
 * <p>It is also the scope of the domain's pull: what it hides is not synced.
 */
final class MergedFilter implements SyncScope {
    /** What an account's checkbox reads. */
    enum Tick {
        ON,
        PARTIAL,
        OFF
    }

    private static final String PREFS = "merged-filter";

    private final Context context;
    private final String accountsKey;
    private final String collectionsKey;

    /** Account emails whose items are kept out of the list. */
    private final Set<String> hiddenAccounts;

    /** Collection ids whose items are kept out of the list. */
    private final Set<String> hiddenCollections;

    private MergedFilter(Context context, PimDomain domain) {
        this.context = context;
        this.accountsKey = domain.id + ".accounts";
        this.collectionsKey = domain.id + ".collections";
        this.hiddenAccounts = new HashSet<>(prefs().getStringSet(accountsKey, Set.of()));
        this.hiddenCollections = new HashSet<>(prefs().getStringSet(collectionsKey, Set.of()));
    }

    /** The domain's filter as it was last left. */
    static MergedFilter of(Context context, PimDomain domain) {
        return new MergedFilter(context, domain);
    }

    /** Whether an item of this account and collection takes part. */
    boolean accepts(String account, String collection) {
        return showsAccount(account) && !hiddenCollections.contains(collection);
    }

    /** Whether the account takes part at all, whatever its collections. */
    boolean showsAccount(String account) {
        return AccountActivation.enabled(context, account) && !hiddenAccounts.contains(account);
    }

    @Override
    public boolean account(String email) {
        return showsAccount(email);
    }

    @Override
    public boolean collection(String email, String collection) {
        return accepts(email, collection);
    }

    /** Whether anything is hidden, which the bar icon reflects. */
    boolean isActive() {
        return !hiddenAccounts.isEmpty() || !hiddenCollections.isEmpty();
    }

    /**
     * What an account's checkbox reads over its collections: unticked when
     * the account is hidden and only then, a blank box meaning its
     * collections are folded away; otherwise ticked when they all show,
     * partly ticked when some or none do.
     */
    Tick tick(String account, Collection<String> collections) {
        if (hiddenAccounts.contains(account)) {
            return Tick.OFF;
        }
        for (String collection : collections) {
            if (hiddenCollections.contains(collection)) {
                return Tick.PARTIAL;
            }
        }
        return Tick.ON;
    }

    /** Whether a collection shows: it and its account. */
    boolean ticked(String account, String collection) {
        return !hiddenAccounts.contains(account) && chosen(collection);
    }

    /** Whether a collection's own checkbox reads ticked, whatever its account. */
    boolean chosen(String collection) {
        return !hiddenCollections.contains(collection);
    }

    /**
     * Flips an account's checkbox: a shown account is hidden, a hidden one
     * comes back, its collections keeping their own choices either way.
     */
    void toggleAccount(String account) {
        if (!hiddenAccounts.remove(account)) {
            hiddenAccounts.add(account);
        }
        save();
    }

    /** Flips one collection's own checkbox. */
    void toggleCollection(String collection) {
        if (!hiddenCollections.remove(collection)) {
            hiddenCollections.add(collection);
        }
        save();
    }

    /** Shows everything again. */
    void reset() {
        hiddenAccounts.clear();
        hiddenCollections.clear();
        save();
    }

    /** Drops what the filter held of an account that is gone. */
    void forget(String account, Collection<String> collections) {
        hiddenAccounts.remove(account);
        hiddenCollections.removeAll(collections);
        save();
    }

    private SharedPreferences prefs() {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private void save() {
        prefs().edit()
                .putStringSet(accountsKey, new HashSet<>(hiddenAccounts))
                .putStringSet(collectionsKey, new HashSet<>(hiddenCollections))
                .apply();
    }
}
