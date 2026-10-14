package org.pimalaya.contact;

import org.pimalaya.client.Addressbook;
import org.pimalaya.client.Card;

/**
 * One replica of the pool: a card, its book and index columns.
 *
 * <p>The write-time index (docs/merged-view.md) keeps rendering parse-free.
 */
public final class Entry {
    /** The addressbook the replica lives in. */
    public final Addressbook book;
    /** The email of the book's account. */
    public final String accountEmail;
    /** The card itself. */
    public final Card card;
    final String name;
    final String email;
    final String phone;
    final String info;
    final String uid;
    /** The normalized content hash replicas compare by. */
    public final String hash;
    /** Whether the replica is a sync conflict awaiting the user. */
    public final boolean conflicted;

    /** The replica's display name from its index, id fallback. */
    public String displayName() {
        return name.isEmpty() ? card.id() : name;
    }

    Entry(Addressbook book, String accountEmail, PimdirContacts.Indexed indexed) {
        this.book = book;
        this.accountEmail = accountEmail;
        this.card = indexed.card;
        this.name = indexed.name;
        this.email = indexed.email;
        this.phone = indexed.phone;
        this.info = indexed.info;
        this.uid = indexed.uid;
        this.hash = indexed.hash;
        this.conflicted = indexed.conflicted;
    }
}
