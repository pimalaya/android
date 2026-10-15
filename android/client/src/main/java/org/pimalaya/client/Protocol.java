package org.pimalaya.client;

/**
 * The protocol family a screen labels an account by.
 *
 * <p>Mirrors rs/account.rs {@code Protocol} 240.
 */
public enum Protocol {
    /** The domain's open standard: IMAP, CalDAV or CardDAV. */
    STANDARD("standard"),
    /** Microsoft Graph. */
    GRAPH("graph"),
    /** JMAP (RFC 8620). */
    JMAP("jmap"),
    /** Google's APIs: Gmail, Calendar, People. */
    GOOGLE("google"),
    /** The on-device account, with no transport. */
    LOCAL("local");

    private final String wire;

    Protocol(String wire) {
        this.wire = wire;
    }

    static Protocol of(String wire) {
        for (Protocol value : values()) {
            if (value.wire.equals(wire)) {
                return value;
            }
        }
        throw new PimalayaException("Unreadable bridge reply: unknown protocol '" + wire + "'");
    }
}
