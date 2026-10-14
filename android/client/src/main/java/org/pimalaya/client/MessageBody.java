package org.pimalaya.client;

import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * One message read whole: the headers a reader sees and the one body.
 *
 * <p>Mirrors rs/mail.rs {@code MessageBody} 175. The bridge resolves the MIME
 * tree: the part shown is the richest alternative, HTML over text.
 *
 * @param subject the decoded {@code Subject}, empty when there is none
 * @param from the sender's display name, empty when they sent none
 * @param fromAddress the sender's address itself
 * @param to every {@code To} address, comma separated, as the header reads
 * @param cc every {@code Cc} address, comma separated
 * @param date when the message was sent, RFC 3339; empty when unreadable
 * @param kind {@link #HTML}, {@link #PLAIN} or empty
 * @param body the body itself, decoded to text
 * @param attachments what the message carries beside its body
 * @param attachmentMark the parts walk's attachment mark (pimdir STORAGE
 *     Annex A.1), replacing the listing's top-level {@code Content-Type} one
 */
public record MessageBody(
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
    /** What {@link #body} holds: {@code html}, {@code plain}, or empty. */
    public static final String HTML = "html";

    /** The plain-text kind, as {@link #kind} spells it. */
    public static final String PLAIN = "plain";

    /** A {@code parseMessage} reply as what a reader draws. */
    static MessageBody from(JSONObject reply) {
        // NOTE: optString and not the null-returning helper beside it:
        // every one of these fields is always serialised, and a reader
        // must never be handed a null to render.
        return new MessageBody(
                reply.optString("subject"),
                reply.optString("from"),
                reply.optString("fromAddress"),
                reply.optString("to"),
                reply.optString("cc"),
                reply.optString("date"),
                reply.optString("kind"),
                reply.optString("body"),
                Attachment.list(reply.optJSONArray("attachments")),
                reply.optBoolean("attachmentMark"));
    }

    /**
     * One attachment, named and measured but not carried.
     *
     * <p>Mirrors rs/mail.rs {@code MessageAttachment} 208. Its bytes stay in
     * the message, read by section ({@link MailClient#messagePart}).
     *
     * @param name the file name, empty when the part names none
     * @param mime the media type, lowercased; empty when the part states none
     * @param size the decoded size in octets
     * @param part the part's IMAP section (RFC 3501 6.4.5), e.g. {@code 1.2}
     */
    public record Attachment(String name, String mime, long size, String part) {
        /** The attachments a parse or a description lists. */
        static List<Attachment> list(JSONArray listed) {
            List<Attachment> attachments = new ArrayList<>(listed == null ? 0 : listed.length());
            for (int index = 0; listed != null && index < listed.length(); index++) {
                JSONObject attachment = Json.object(listed, index);
                attachments.add(
                        new Attachment(
                                attachment.optString("name"),
                                attachment.optString("mime"),
                                attachment.optLong("size"),
                                attachment.optString("part")));
            }
            return attachments;
        }
    }
}
