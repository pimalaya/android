package org.pimalaya.account;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.Collection;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * One list's filter: which accounts and which of their collections it
 * shows, keyed by collection id and kept across restarts (the filter page,
 * {@link FilterPage}).
 *
 * <p>One per domain, since the collections are the domain's own, and one for
 * the Files tab, shared by the process so the lists and a background run
 * read the same one. State is kept as the <em>hidden</em> sets rather than
 * the shown ones, so a collection listed later shows up without having to be
 * enrolled. The junk and the trash are the exception: each is hidden once,
 * the first time its roster lists it ({@link #quiet}), and showing it again
 * is the user's choice to make; a Reset goes back to that.
 *
 * <p>An account is hidden apart from its collections, so hiding it keeps
 * which of them were hidden for when it comes back, the page folding them
 * away meanwhile. An account that is off
 * ({@link AccountActivation}) is never shown, whatever the filter says.
 *
 * <p>It is also the scope of the domain's syncs: what it hides is not synced.
 */
public final class MergedFilter implements SyncScope {
    /** What an account's checkbox reads. */
    public enum Tick {
        ON,
        PARTIAL,
        OFF
    }

    /** The process holding the filters, which the app is. */
    public interface Owner {
        /** The process's filters, by list. */
        Map<String, MergedFilter> filters();
    }

    private static final String PREFS = "merged-filter";

    private final Context context;
    private final String accountsKey;
    private final String collectionsKey;
    private final String quietKey;

    /** Account emails whose items are kept out of the list. */
    private final Set<String> hiddenAccounts;

    /** Collection ids whose items are kept out of the list. */
    private final Set<String> hiddenCollections;

    /** The collections hidden by default (junk, trash), once each was listed. */
    private final Set<String> quietCollections;

    private MergedFilter(Context context, String list) {
        this(context, list + ".accounts", list + ".collections", list + ".quiet");
    }

    private MergedFilter(
            Context context, String accountsKey, String collectionsKey, String quietKey) {
        this.context = context;
        this.accountsKey = accountsKey;
        this.collectionsKey = collectionsKey;
        this.quietKey = quietKey;
        this.hiddenAccounts = new HashSet<>(prefs().getStringSet(accountsKey, Set.of()));
        this.hiddenCollections = new HashSet<>(prefs().getStringSet(collectionsKey, Set.of()));
        this.quietCollections = new HashSet<>(prefs().getStringSet(quietKey, Set.of()));
    }

    /** Whether this is a draft ({@link #draft}), kept nowhere until applied. */
    private boolean drafting;

    /**
     * A copy of this filter for the filter page to edit, kept nowhere: the
     * page applies it ({@link #apply}) or drops it.
     */
    public synchronized MergedFilter draft() {
        MergedFilter draft = new MergedFilter(context, accountsKey, collectionsKey, quietKey);
        draft.drafting = true;
        draft.hiddenAccounts.clear();
        draft.hiddenCollections.clear();
        draft.quietCollections.clear();
        draft.hiddenAccounts.addAll(hiddenAccounts);
        draft.hiddenCollections.addAll(hiddenCollections);
        draft.quietCollections.addAll(quietCollections);
        return draft;
    }

    /** Takes a draft's choices and keeps them. */
    public synchronized void apply(MergedFilter draft) {
        synchronized (draft) {
            hiddenAccounts.clear();
            hiddenAccounts.addAll(draft.hiddenAccounts);
            hiddenCollections.clear();
            hiddenCollections.addAll(draft.hiddenCollections);
        }
        save();
    }

    /** The domain's filter as it was last left. */
    public static MergedFilter of(Context context, PimDomain domain) {
        return shared(context, domain.id);
    }

    /** The Files tab's filter, which no account connects as a domain. */
    public static MergedFilter files(Context context) {
        return shared(context, "files");
    }

    private static MergedFilter shared(Context context, String list) {
        Context application = context.getApplicationContext();
        Map<String, MergedFilter> filters = ((Owner) application).filters();
        synchronized (filters) {
            return filters.computeIfAbsent(list, key -> new MergedFilter(application, key));
        }
    }

    /** Whether an item of this account and collection takes part. */
    public synchronized boolean accepts(String account, String collection) {
        return showsAccount(account) && !hiddenCollections.contains(collection);
    }

    /** Whether the account takes part at all, whatever its collections. */
    public synchronized boolean showsAccount(String account) {
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

    /**
     * Whether anything is hidden beyond what is hidden by default, which the
     * bar icon reflects.
     */
    public synchronized boolean isActive() {
        return !hiddenAccounts.isEmpty() || !quietCollections.containsAll(hiddenCollections);
    }

    /**
     * What an account's checkbox reads over its collections: unticked when
     * the account is hidden and only then, a blank box meaning its
     * collections are folded away; otherwise ticked when they all show,
     * partly ticked when some or none do.
     */
    public synchronized Tick tick(String account, Collection<String> collections) {
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
    public synchronized boolean ticked(String account, String collection) {
        return !hiddenAccounts.contains(account) && chosen(collection);
    }

    /** Whether a collection's own checkbox reads ticked, whatever its account. */
    public synchronized boolean chosen(String collection) {
        return !hiddenCollections.contains(collection);
    }

    /**
     * Flips an account's checkbox: a shown account is hidden, a hidden one
     * comes back, its collections keeping their own choices either way.
     */
    public synchronized void toggleAccount(String account) {
        if (!hiddenAccounts.remove(account)) {
            hiddenAccounts.add(account);
        }
        save();
    }

    /** Flips one collection's own checkbox. */
    public synchronized void toggleCollection(String collection) {
        if (!hiddenCollections.remove(collection)) {
            hiddenCollections.add(collection);
        }
        save();
    }

    /**
     * Hides the collections hidden by default (an account's junk and trash)
     * that were never listed before, and only those: one the user showed
     * since stays shown.
     */
    public synchronized void quiet(Collection<String> collections) {
        boolean changed = false;
        for (String collection : collections) {
            if (quietCollections.add(collection)) {
                hiddenCollections.add(collection);
                changed = true;
            }
        }
        if (changed) {
            save();
        }
    }

    /** Shows everything again, but what is hidden by default. */
    public synchronized void reset() {
        hiddenAccounts.clear();
        hiddenCollections.clear();
        hiddenCollections.addAll(quietCollections);
        save();
    }

    /** Drops what the filter held of an account that is gone. */
    public synchronized void forget(String account, Collection<String> collections) {
        hiddenAccounts.remove(account);
        hiddenCollections.removeAll(collections);
        quietCollections.removeAll(collections);
        save();
    }

    private SharedPreferences prefs() {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private void save() {
        if (drafting) {
            return;
        }
        prefs().edit()
                .putStringSet(accountsKey, new HashSet<>(hiddenAccounts))
                .putStringSet(collectionsKey, new HashSet<>(hiddenCollections))
                .putStringSet(quietKey, new HashSet<>(quietCollections))
                .apply();
    }
}
