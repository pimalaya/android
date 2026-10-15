package org.pimalaya.pimdir;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.Map;
import java.util.UUID;

/**
 * An account's stable store id, and the namespacing of its collection ids.
 *
 * <p>A UUID minted once, never the editable email, and free of the separator
 * so a prefix strips unambiguously (pimdir SPEC.md §9.2). The store has no
 * account row: the roster stays in {@link org.pimalaya.account.SecureStore}.
 */
public final class PimdirAccount {
    /** The separator between the account id and the collection name. */
    public static final char SEPARATOR = '/';

    private static final String PREFS = "pimdir-accounts";

    private final SharedPreferences prefs;

    /** The id mapping kept in the app's preferences. */
    public PimdirAccount(Context context) {
        this.prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /**
     * The store id of the account {@code email}, minted on first ask.
     *
     * <p>The email is the lookup key here only, so changing it later remaps
     * these preferences instead of rewriting every collection.
     */
    public synchronized String idOf(String email) {
        String id = prefs.getString(email, null);
        if (id == null) {
            id = UUID.randomUUID().toString();
            prefs.edit().putString(email, id).apply();
        }
        return id;
    }

    /** The address behind a store id ({@link #idOf} reversed), or null. */
    synchronized String emailOf(String id) {
        for (Map.Entry<String, ?> entry : prefs.getAll().entrySet()) {
            if (id.equals(entry.getValue())) {
                return entry.getKey();
            }
        }
        return null;
    }

    /** The account id a collection id is namespaced under. */
    public static String accountIdOf(String collectionId) {
        int at = collectionId.indexOf(SEPARATOR);
        return at < 0 ? collectionId : collectionId.substring(0, at);
    }

    /** The collection id of {@code name} within the account. */
    public static String collectionId(String accountId, String name) {
        return accountId + SEPARATOR + name;
    }

    /**
     * The collection name inside a namespaced id, else the id itself.
     *
     * <p>Strips the account id and exactly one separator, so a hierarchical
     * name keeps its own.
     */
    public static String nameOf(String accountId, String collectionId) {
        String prefix = accountId + SEPARATOR;
        return collectionId.startsWith(prefix)
                ? collectionId.substring(prefix.length())
                : collectionId;
    }
}
