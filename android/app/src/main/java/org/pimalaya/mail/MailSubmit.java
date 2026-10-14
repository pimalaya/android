package org.pimalaya.mail;

import org.json.JSONException;
import org.json.JSONObject;
import org.pimalaya.client.Account;
import org.pimalaya.client.AccountClient;
import org.pimalaya.client.Flags;
import org.pimalaya.client.MailClient;
import org.pimalaya.pimdir.PimdirSummary;

import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;
import java.util.UUID;

/**
 * Puts one composed message in its account's outbox.
 *
 * <p>Nothing is handed over here, and that is the point: composing reaches
 * for nothing, so a message can be written and sent with the radio off, and
 * the sync that follows is what carries it out. What the composer waits for
 * is a disk write.
 */
public final class MailSubmit {
    /** The pattern an RFC 5322 date is written in (section 3.3). */
    private static final String DATE_FORMAT = "EEE, d MMM yyyy HH:mm:ss Z";

    private final MailClient client;
    private final MailStore mail;

    /** The sender's driver, staging the sent copy. */
    private final MailStager engine;

    public MailSubmit(MailClient client, MailStore mail, MailStager engine) {
        this.client = client;
        this.mail = mail;
        this.engine = engine;
    }

    /**
     * What the composer's fields say, read on the main thread: the sender,
     * the three address fields comma separated, the subject, the body and
     * the reply's headers (empty outside a reply).
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
     * Queues a draft ({@link #queue(JSONObject)}), stamped now and under a
     * fresh `Message-ID`.
     *
     * <p>The two stamps are minted here rather than in the bridge, which
     * keeps the composition a pure function of what it is handed: the
     * date is this device's clock and the identifier is a fresh one under
     * the sender's own domain, which is what RFC 5322 section 3.6.4 asks
     * of whoever mints it.
     */
    public void queue(Draft draft) throws JSONException {
        queue(
                new JSONObject()
                        .put("from", draft.from())
                        .put("fromName", draft.fromName())
                        .put("to", draft.to())
                        .put("cc", draft.cc())
                        .put("bcc", draft.bcc())
                        .put("subject", draft.subject())
                        .put("body", draft.body())
                        .put("date", now())
                        .put("messageId", messageId(draft.from()))
                        .put("inReplyTo", draft.inReplyTo())
                        .put("references", draft.references()));
    }

    /**
     * Composes the draft (the bridge's shape: {@code from}, {@code to},
     * {@code cc}, {@code bcc}, {@code subject}, {@code body}, {@code date},
     * {@code messageId} and the reply's headers), queues its submission and
     * stages its sent copy.
     *
     * <p>The `Message-ID` is the identity, which is what the payload names it
     * by: it is minted before and stamped on the message, so the copy the
     * sync files in the sent mailbox comes back under the same name.
     */
    public void queue(JSONObject draft) throws JSONException {
        String sender = draft.getString("from");
        String messageId = draft.getString("messageId");
        String subject = draft.optString("subject");
        String date = draft.getString("date");

        byte[] source = client.composeMessage(draft.toString());
        mail.queueSubmission(
                sender,
                PimdirSummary.bare(messageId),
                subject,
                PimdirSummary.mailSortKey(date),
                source);
        stageSentCopy(sender, messageId, subject, draft.optString("to"), date, source);
    }

    /**
     * Stages the copy the sender keeps in the account's sent mailbox beside
     * the submission, so it shows there at once. The sync carries it out:
     * the provider filing sent mail itself (Gmail, Graph, JMAP), its listing
     * lands the copy by the `Message-ID`; elsewhere the copy is appended
     * once the submission went (pimdir SYNC §5).
     *
     * <p>None for an account marking no sent mailbox.
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

    /**
     * Whether a reply marks its parent answered once queued.
     *
     * <p>Graph and Gmail keep no answered marker, and one staged there
     * would be refused on every sync.
     */
    static boolean marksAnswered(Account server) {
        return !AccountClient.isGraph(server) && !AccountClient.isGoogle(server);
    }

    /** Now, as the date RFC 5322 section 3.3 writes. */
    public static String now() {
        SimpleDateFormat format = new SimpleDateFormat(DATE_FORMAT, Locale.US);
        format.setTimeZone(TimeZone.getDefault());
        return format.format(new Date());
    }

    /**
     * A fresh `Message-ID` under the sender's own domain, which is the one
     * RFC 5322 section 3.6.4 says whoever mints it should use.
     */
    public static String messageId(String email) {
        int at = email.lastIndexOf('@');
        String domain = at < 0 ? "pimalaya.android" : email.substring(at + 1);
        return "<" + UUID.randomUUID() + "@" + domain + ">";
    }
}
