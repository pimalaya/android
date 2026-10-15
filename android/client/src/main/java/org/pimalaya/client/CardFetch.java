package org.pimalaya.client;

/**
 * How a backend reads changed cards in bulk.
 *
 * <p>Mirrors rs/account.rs {@code CardFetch} 201.
 */
public enum CardFetch {
    /** One {@code addressbook-multiget} REPORT per chunk (RFC 6352 8.7). */
    MULTIGET("multiget"),
    /** JSON batching of single reads, a complete listing priming bodies. */
    JSON_BATCH("jsonBatch"),
    /** The changes carry the cards, one missing read alone. */
    CHANGES("changes"),
    /** One read naming many cards. */
    BATCH_GET("batchGet");

    private final String wire;

    CardFetch(String wire) {
        this.wire = wire;
    }

    static CardFetch of(String wire) {
        for (CardFetch value : values()) {
            if (value.wire.equals(wire)) {
                return value;
            }
        }
        throw new PimalayaException("Unreadable bridge reply: unknown card fetch '" + wire + "'");
    }
}
