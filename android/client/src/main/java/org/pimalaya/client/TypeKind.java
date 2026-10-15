package org.pimalaya.client;

/**
 * The edit form's type spinners, each with its own ordered type vocabulary.
 *
 * <p>Mirrors rs/contact/form.rs {@code card_type_order} 221.
 */
public enum TypeKind {
    /** The phone TYPE sets. */
    PHONE("phone"),
    /** The email TYPE sets. */
    EMAIL("email"),
    /** The address TYPE sets. */
    ADDRESS("address"),
    /** The relation TYPE sets. */
    RELATION("relation"),
    /** The gender sex codes, one per position. */
    GENDER("gender");

    private final String wire;

    TypeKind(String wire) {
        this.wire = wire;
    }

    /** The value as the bridge spells it. */
    String wire() {
        return wire;
    }
}
