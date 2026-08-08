package org.pimalaya.client;

/**
 * One message's envelope spine.
 *
 * <p>The spine only: no body, no structure, no attachments. A merged
 * mail list renders exactly these fields, and fetching bodies for every
 * message of every mailbox to draw a list would be the wrong trade.
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

    /** First {@code From} address, display name preferred. */
    public final String from;

    /** Envelope {@code Date}, still RFC 5322 text. */
    public final String date;

    /** Whether the message carries {@code \Seen}. */
    public final boolean seen;

    public Message(
            String mailbox, String id, String subject, String from, String date, boolean seen) {
        this.mailbox = mailbox;
        this.id = id;
        this.subject = subject;
        this.from = from;
        this.date = date;
        this.seen = seen;
    }
}
