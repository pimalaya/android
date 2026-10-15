package org.pimalaya.mail;

import org.json.JSONException;
import org.pimalaya.client.ComposeDraft;
import org.pimalaya.client.Flags;
import org.pimalaya.client.MailClient;
import org.pimalaya.pimdir.PimdirSummary;

import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.Objects;
import java.util.TimeZone;
import java.util.UUID;

/**
 * Puts one composed message in its account's outbox.
 *
 * <p>Nothing is handed over here: the next sync carries it out, so a message is
 * sent with the radio off and the composer waits for a disk write only.
 */
public final class MailSubmit {
    /** The pattern an RFC 5322 date is written in (section 3.3). */
    private static final String DATE_FORMAT = "EEE, d MMM yyyy HH:mm:ss Z";

    private final MailClient client;
    private final MailStore mail;

    /** The sender's driver, staging the sent copy. */
    private final MailStager engine;

    /** Queues into {@code mail}, staging sent copies on {@code engine}. */
    public MailSubmit(MailClient client, MailStore mail, MailStager engine) {
        this.client = client;
        this.mail = mail;
        this.engine = engine;
    }

    /**
     * What the composer's fields say, read on the main thread.
     *
     * <p>Address fields comma separated; the reply's headers empty outside one.
     */
    public record Draft(
            String from,
            String fromName,
            String to,
            String cc,
            String bcc,
            String subject,
            String body,
            String inReplyTo,
            String references) {}

    /**
     * Queues a draft stamped now under a fresh `Message-ID`.
     *
     * <p>Minted here so composition stays a pure function: the device's clock,
     * an id under the sender's domain (RFC 5322 section 3.6.4).
     */
    public void queue(Draft draft) throws JSONException {
        queue(
                new ComposeDraft(
                        draft.from(),
                        draft.fromName(),
                        draft.to(),
                        draft.cc(),
                        draft.bcc(),
                        draft.subject(),
                        draft.body(),
                        now(),
                        messageId(draft.from()),
                        draft.inReplyTo(),
                        draft.references()));
    }

    /**
     * Composes the draft, queues its submission and stages its sent copy.
     *
     * <p>The `Message-ID` is the identity the payload names it by, stamped on
     * the message, so the sent copy the sync files comes back under it.
     */
    public void queue(ComposeDraft draft) throws JSONException {
        String sender = draft.from();
        String messageId = draft.messageId();
        String subject = Objects.requireNonNullElse(draft.subject(), "");
        String date = draft.date();

        byte[] source = client.compose(draft);
        mail.outbox().queueSubmission(
                Submission.of(
                        sender,
                        PimdirSummary.bare(messageId),
                        subject,
                        PimdirSummary.mailSortKey(date)),
                source);
        stageSentCopy(
                sender, messageId, subject, Objects.requireNonNullElse(draft.to(), ""), date, source);
    }

    /**
     * Stages the sender's copy in the sent mailbox, shown at once.
     *
     * <p>Gmail, Graph and JMAP file sent mail: their listing lands it by
     * `Message-ID`; elsewhere it is appended once sent (pimdir SYNC §5). None
     * for an account marking no sent mailbox.
     */
    private void stageSentCopy(
            String sender, String messageId, String subject, String to, String date, byte[] source)
            throws JSONException {
        String sent = mail.sentOf(sender);
        if (sent == null) {
            return;
        }
        engine.mutateAdd(
                sent,
                PimdirSummary.bare(messageId),
                // NOTE: the composition is 7-bit (RFC 2047 words, a
                // quoted-printable body), so its text is its bytes.
                new String(source, StandardCharsets.UTF_8),
                Flags.of(Flags.Marker.SEEN),
                PimdirSummary.mail(
                        messageId,
                        subject,
                        null,
                        sender,
                        to.split(",")[0].trim(),
                        date,
                        source.length,
                        false),
                PimdirSummary.mailSortKey(date));
    }

    /** Now, as the date RFC 5322 section 3.3 writes. */
    public static String now() {
        SimpleDateFormat format = new SimpleDateFormat(DATE_FORMAT, Locale.US);
        format.setTimeZone(TimeZone.getDefault());
        return format.format(new Date());
    }

    /** A fresh `Message-ID` under the sender's domain (RFC 5322 3.6.4). */
    public static String messageId(String email) {
        int at = email.lastIndexOf('@');
        String domain = at < 0 ? "pimalaya.android" : email.substring(at + 1);
        return "<" + UUID.randomUUID() + "@" + domain + ">";
    }
}
