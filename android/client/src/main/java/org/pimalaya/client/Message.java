package org.pimalaya.client;

/**
 * One message's envelope spine.
 *
 * <p>The spine only: no body and no MIME structure, just the one bit of
 * it a row renders ({@link #hasAttachment}). A merged mail list draws
 * exactly these fields, and fetching bodies for every message of every
 * mailbox to draw a list would be the wrong trade.
 */
public final class Message {
    /** The mailbox the message was listed from. */
    public final String mailbox;

    /**
     * What the backend addresses the message by, within its mailbox: the
     * IMAP UID as text, the opaque {@code Email} id on JMAP. A string
     * rather than a number because only one of the two is one.
     */
    public final String id;

    public final String subject;

    /**
     * First {@code From} display name, empty when the sender sent none.
     * Kept apart from the address rather than folded into one label,
     * because a row shows the name while the avatar beside it is derived
     * from the address, which is the half that never changes.
     */
    public final String from;

    /** First {@code From} address itself, empty when there is no sender. */
    public final String fromAddress;

    /** Envelope {@code Date}, still RFC 5322 text. */
    public final String date;

    /** Whether the message carries {@code \Seen} (JMAP {@code $seen}). */
    public final boolean seen;

    /** Whether the message carries {@code \Answered}. */
    public final boolean answered;

    /** Whether the message carries {@code \Flagged}. */
    public final boolean flagged;

    /** Whether any MIME part is dispositioned as an attachment. */
    public final boolean hasAttachment;

    public Message(
            String mailbox,
            String id,
            String subject,
            String from,
            String fromAddress,
            String date,
            boolean seen,
            boolean answered,
            boolean flagged,
            boolean hasAttachment) {
        this.mailbox = mailbox;
        this.id = id;
        this.subject = subject;
        this.from = from;
        this.fromAddress = fromAddress;
        this.date = date;
        this.seen = seen;
        this.answered = answered;
        this.flagged = flagged;
        this.hasAttachment = hasAttachment;
    }
}
