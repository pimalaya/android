package org.pimalaya.client;

/**
 * How a mail step walks an account.
 *
 * <p>Mirrors rs/account.rs {@code MailStepKind} 228.
 */
public enum MailStepKind {
    /** The account as one listing, newest first. */
    ACCOUNT("account"),
    /** Each stepped mailbox by date. */
    MAILBOXES("mailboxes"),
    /** Widened from the store, the server listing per mailbox. */
    STORE("store");

    private final String wire;

    MailStepKind(String wire) {
        this.wire = wire;
    }

    static MailStepKind of(String wire) {
        for (MailStepKind value : values()) {
            if (value.wire.equals(wire)) {
                return value;
            }
        }
        throw new PimalayaException("Unreadable bridge reply: unknown step kind '" + wire + "'");
    }
}
