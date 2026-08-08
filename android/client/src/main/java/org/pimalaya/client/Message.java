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

    /** IMAP UID, unique within the mailbox and its UIDVALIDITY epoch. */
    public final long uid;

    public final String subject;

    /** First {@code From} address, display name preferred. */
    public final String from;

    /** Envelope {@code Date}, still RFC 5322 text. */
    public final String date;

    /** Whether the message carries {@code \Seen}. */
    public final boolean seen;

    public Message(
            String mailbox, long uid, String subject, String from, String date, boolean seen) {
        this.mailbox = mailbox;
        this.uid = uid;
        this.subject = subject;
        this.from = from;
        this.date = date;
        this.seen = seen;
    }
}
