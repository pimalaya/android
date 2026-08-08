package org.pimalaya;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.Map;
import java.util.UUID;

/**
 * The stable id an account is grouped under in the pimdir store, and the
 * namespacing of its collection ids.
 *
 * <p>Two rules from pimdir SPEC.md §9.2 shape this, and the app would break
 * both if it used the obvious thing:
 *
 * <ul>
 *   <li><strong>The id must not change.</strong> The account id becomes part of
 *       every collection id it namespaces, so naming it after something the
 *       user can rename turns a rename into an id change for every collection.
 *       An email address looks stable and is not: people change providers and
 *       correct typos. So the id is a UUID generated once, kept beside the
 *       account's configuration, and never derived from anything the user can
 *       edit.
 *   <li><strong>The separator must not appear in the id.</strong> A collection
 *       name is routinely hierarchical ({@code [Gmail]/All Mail}) and has to
 *       survive, so the prefix is stripped by removing the known account id and
 *       one separator. That only works if the id itself contains no separator:
 *       account {@code a} holding {@code b/c} and account {@code a/b} holding
 *       {@code c} would otherwise spell the same string, and nothing could tell
 *       them apart afterwards. A UUID has no slash, so the rule holds by
 *       construction rather than by validation.
 * </ul>
 *
 * <p>The store holds no account row: it learns an account only through its
 * collections. The roster, the credentials and the display name stay where they
 * already are, in {@link SecureStore}, and this class is only the bridge between
 * that roster and the store's grouping key.
 */
final class PimdirAccount {
    /** The separator between the account id and the collection name. */
    static final char SEPARATOR = '/';

    private static final String PREFS = "pimdir-accounts";

    private final SharedPreferences prefs;

    PimdirAccount(Context context) {
        this.prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /**
     * The store id of the account identified by {@code email}, minting and
     * remembering one the first time it is asked for.
     *
     * <p>The email is the lookup key here and nowhere else: it maps to the id,
     * and the id is what reaches the store, so changing the email later is a
     * remap in these preferences rather than a rewrite of every collection.
     */
    synchronized String idOf(String email) {
        String id = prefs.getString(email, null);
        if (id == null) {
            id = UUID.randomUUID().toString();
            prefs.edit().putString(email, id).apply();
        }
        return id;
    }

    /**
     * The address behind a store id, or null when no account claims it.
     *
     * <p>The reverse of {@link #idOf}, by scanning: the mapping is one entry per
     * connected account, so a scan is cheaper than the second index that would
     * keep it sorted, and this runs when a collection roster is rendered rather
     * than per item.
     */
    synchronized String emailOf(String id) {
        for (Map.Entry<String, ?> entry : prefs.getAll().entrySet()) {
            if (id.equals(entry.getValue())) {
                return entry.getKey();
            }
        }
        return null;
    }

    /** Re-points an account's stored id at a new address, keeping the id. */
    synchronized void rename(String oldEmail, String newEmail) {
        String id = idOf(oldEmail);
        prefs.edit().remove(oldEmail).putString(newEmail, id).apply();
    }

    /** Forgets an account, so removing and re-adding it starts a fresh store id. */
    synchronized void forget(String email) {
        prefs.edit().remove(email).apply();
    }

    /** The collection id of {@code name} within the account. */
    static String collectionId(String accountId, String name) {
        return accountId + SEPARATOR + name;
    }

    /**
     * The collection name inside a namespaced id, or the id itself when it does
     * not belong to the account.
     *
     * <p>Strips the account id and exactly one separator, so a hierarchical
     * name keeps every separator of its own.
     */
    static String nameOf(String accountId, String collectionId) {
        String prefix = accountId + SEPARATOR;
        return collectionId.startsWith(prefix)
                ? collectionId.substring(prefix.length())
                : collectionId;
    }
}
