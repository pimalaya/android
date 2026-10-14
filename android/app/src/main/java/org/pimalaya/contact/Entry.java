package org.pimalaya.contact;

import org.pimalaya.client.Addressbook;
import org.pimalaya.client.Card;

/**
 * One replica in the contacts pool: a card, the addressbook it lives
 * in, and its write-time index columns (docs/merged-view.md), so
 * rendering never parses a vCard.
 */
public final class Entry {
    public final Addressbook book;
    public final String accountEmail;
    public final Card card;
    final String name;
    final String email;
    final String phone;
    final String info;
    final String uid;
    public final String hash;
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
