package org.pimalaya.contact;

import org.pimalaya.client.Addressbook;
import org.pimalaya.client.Card;

/**
 * One replica of the pool: its book, and what its row shows.
 *
 * <p>The row is the store's summary (pimdir STORAGE Annex A.2), so rendering
 * reads no body; {@link #card} reads it when a flow needs the card itself.
 */
public final class Entry {
    /** The addressbook the replica lives in. */
    public final Addressbook book;
    /** The email of the book's account. */
    public final String accountEmail;
    /** The card's link id. */
    public final String id;
    final String name;
    final String email;
    final String info;
    final String uid;
    /** Its {@code KIND}, lowercased, {@code individual} when unstated. */
    final String kind;
    /** Whether the replica is a sync conflict awaiting the user. */
    public final boolean conflicted;

    private final PimdirContacts.Indexed row;

    /** The replica's display name from its summary, id fallback. */
    public String displayName() {
        return name.isEmpty() ? id : name;
    }

    /** The card itself, its body read on first use. */
    public Card card() {
        return row.card();
    }

    /** The normalized content hash replicas compare by, off its body. */
    public String hash() {
        return row.hash();
    }

    /** The store's hash of its body, equal for equal bytes. */
    String object() {
        return row.object;
    }

    /** Its collection and seq, as the search index keys a placement. */
    String key() {
        return book.url() + "\n" + row.seq;
    }

    /** Its account and seq, as a search hit names it. */
    String hit() {
        return row.account + "\n" + row.seq;
    }

    Entry(Addressbook book, String accountEmail, PimdirContacts.Indexed indexed) {
        this.book = book;
        this.accountEmail = accountEmail;
        this.row = indexed;
        this.id = indexed.id;
        this.name = indexed.name;
        this.email = indexed.email;
        this.info = indexed.info;
        this.uid = indexed.uid;
        this.kind = indexed.kind;
        this.conflicted = indexed.conflicted;
    }
}
