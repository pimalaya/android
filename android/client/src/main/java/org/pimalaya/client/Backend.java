package org.pimalaya.client;

/**
 * The backend behind an account base URL.
 *
 * <p>Mirrors rs/account.rs {@code Backend} 119, plus the on-device account
 * (rs/ffi/account.rs 640).
 */
public enum Backend {
    /** A plain CardDAV (or CalDAV, IMAP) server: the default. */
    CARDDAV("carddav"),
    /** Microsoft Graph. */
    GRAPH("graph"),
    /** A JMAP server (RFC 8620). */
    JMAP("jmap"),
    /** Google's APIs: People, Gmail, Calendar. */
    GOOGLE("google"),
    /** The on-device account, with no transport. */
    LOCAL("local");

    private final String wire;

    Backend(String wire) {
        this.wire = wire;
    }

    /** The value as the bridge spells it. */
    public String wire() {
        return wire;
    }

    static Backend of(String wire) {
        for (Backend backend : values()) {
            if (backend.wire.equals(wire)) {
                return backend;
            }
        }
        throw new PimalayaException("Unreadable bridge reply: unknown backend '" + wire + "'");
    }
}
