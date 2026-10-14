package org.pimalaya.client;

/** What one {@link PushChange} does (rs/contact/push.rs {@code PushChange.op} 20). */
public enum PushOp {
    /** A genuine create, carrying the vCard. */
    CREATE("create"),
    /** A content update, carrying the vCard and the base it edits. */
    UPDATE("update"),
    /** A membership patch, carrying the book ids to add or remove. */
    BOOKS("books"),
    /** The card's deletion. */
    DESTROY("destroy");

    private final String wire;

    PushOp(String wire) {
        this.wire = wire;
    }

    /** The value as the bridge spells it. */
    public String wire() {
        return wire;
    }
}
