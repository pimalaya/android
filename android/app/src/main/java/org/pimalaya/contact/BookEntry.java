package org.pimalaya.contact;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.pimalaya.client.Addressbook;

/**
 * An addressbook with its account and its three switches.
 *
 * <p>subscribed shows its contacts, remoteSynced syncs it with the server,
 * phoneSynced projects it into the Contacts app; both sync switches require the
 * subscription.
 */
public final class BookEntry {
    /** The addressbook. */
    public final Addressbook book;
    /** The email of the account it belongs to. */
    public final String accountEmail;
    /** Whether its contacts show in the app. */
    public final boolean subscribed;
    /** Whether the sync exchanges it with the server. */
    public final boolean remoteSynced;
    /** Whether it is projected into the Contacts app. */
    public final boolean phoneSynced;

    /** An addressbook entry with its account and switches. */
    public BookEntry(
            Addressbook book,
            String accountEmail,
            boolean subscribed,
            boolean remoteSynced,
            boolean phoneSynced) {
        this.book = book;
        this.accountEmail = accountEmail;
        this.subscribed = subscribed;
        this.remoteSynced = remoteSynced;
        this.phoneSynced = phoneSynced;
    }

    /**
     * Each entry's book with its account's email, in order.
     *
     * <p>What the phone's book accounts are reconciled against.
     */
    public static Map<Addressbook, String> accounts(List<BookEntry> entries) {
        Map<Addressbook, String> accounts = new LinkedHashMap<>();
        for (BookEntry entry : entries) {
            accounts.put(entry.book, entry.accountEmail);
        }
        return accounts;
    }
}
