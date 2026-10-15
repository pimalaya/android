package org.pimalaya.client;

/**
 * How a backend pushes a round of card changes.
 *
 * <p>Mirrors rs/account.rs {@code CardPush} 216.
 */
public enum CardPush {
    /** One guarded request per change, run in parallel: no batch verb. */
    PARALLEL("parallel"),
    /** One request carrying the round. */
    ROUND("round"),
    /** The batch verbs, memberships patched apart. */
    BATCH("batch");

    private final String wire;

    CardPush(String wire) {
        this.wire = wire;
    }

    static CardPush of(String wire) {
        for (CardPush value : values()) {
            if (value.wire.equals(wire)) {
                return value;
            }
        }
        throw new PimalayaException("Unreadable bridge reply: unknown card push '" + wire + "'");
    }
}
