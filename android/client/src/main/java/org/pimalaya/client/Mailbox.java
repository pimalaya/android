package org.pimalaya.client;

/** One mailbox an account holds, and what the server says it is for. */
public final class Mailbox {
    /** The name a collection is keyed by, hierarchical where the backend nests. */
    public final String name;

    /**
     * {@code trash} where the server marks the mailbox with the RFC 6154
     * attribute of that name, empty where it marks it another or none.
     *
     * <p>The one role a write needs: which mailbox a delete moves into. It
     * is read once per sync and stored, because a delete has to decide
     * between a move and a marker with no network to ask.
     */
    public final String role;

    /** The role a mailbox holding the account's deleted messages carries. */
    public static final String TRASH = "trash";

    public Mailbox(String name, String role) {
        this.name = name;
        this.role = role;
    }
}
