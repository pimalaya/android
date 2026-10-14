package org.pimalaya.contact;

import org.pimalaya.client.Addressbook;

/**
 * An addressbook with the account it belongs to and its three switches:
 * subscribed (the book is on: its contacts are visible in the app),
 * remoteSynced (the sync exchanges it with the server) and phoneSynced
 * (it is projected into the phone's Contacts app as its own account).
 * Both sync switches require the subscription, so an off book neither
 * talks to the server nor shows in the Contacts app.
 */
public final class BookEntry {
    public final Addressbook book;
    public final String accountEmail;
    public final boolean subscribed;
    public final boolean remoteSynced;
    public final boolean phoneSynced;

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
}
