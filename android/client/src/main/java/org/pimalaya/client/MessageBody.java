package org.pimalaya.client;

import java.util.List;

/**
 * One message read whole: the headers a reader sees and the one body
 * they read.
 *
 * <p>The MIME tree is resolved on the bridge side, so nothing here is a
 * tree: picking the part to show is a decision about the message (the
 * richest alternative wins, HTML over text) rather than about the screen
 * showing it.
 */
public final class MessageBody {
    /** What {@link #body} holds: {@code html}, {@code plain}, or empty. */
    public static final String HTML = "html";

    /** The plain-text kind, as {@link #kind} spells it. */
    public static final String PLAIN = "plain";

    public final String subject;

    /** The sender's display name, empty when they sent none. */
    public final String from;

    /** The sender's address itself. */
    public final String fromAddress;

    /** Every {@code To} address, comma separated, as the header reads. */
    public final String to;

    /** Every {@code Cc} address, comma separated. */
    public final String cc;

    /** When the message was sent, RFC 3339; empty when unreadable. */
    public final String date;

    /** Which of {@link #HTML} and {@link #PLAIN} the body is, or empty. */
    public final String kind;

    /** The body itself, decoded to text. */
    public final String body;

    /** What the message carries beside its body. */
    public final List<Attachment> attachments;

    /**
     * The attachment mark the walk of the parts gives (pimdir STORAGE Annex
     * A.1), which replaces the one a listing read off the top-level
     * {@code Content-Type} once the body is in.
     */
    public final boolean attachmentMark;

    public MessageBody(
            String subject,
            String from,
            String fromAddress,
            String to,
            String cc,
            String date,
            String kind,
            String body,
            List<Attachment> attachments,
            boolean attachmentMark) {
        this.subject = subject;
        this.from = from;
        this.fromAddress = fromAddress;
        this.to = to;
        this.cc = cc;
        this.date = date;
        this.kind = kind;
        this.body = body;
        this.attachments = attachments;
        this.attachmentMark = attachmentMark;
    }

    /**
     * One attachment, named and measured but not carried: its bytes stay
     * in the message, read by section ({@link PimalayaClient#messagePart}).
     */
    public static final class Attachment {
        /** The file name, empty when the part names none. */
        public final String name;

        /** The media type, lowercased; empty when the part states none. */
        public final String mime;

        /** The decoded size in octets. */
        public final long size;

        /** The IMAP section of the part (RFC 3501 section 6.4.5): {@code 2}, {@code 1.2}. */
        public final String part;

        public Attachment(String name, String mime, long size, String part) {
            this.name = name;
            this.mime = mime;
            this.size = size;
            this.part = part;
        }
    }
}
