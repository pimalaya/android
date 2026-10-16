package org.pimalaya.client;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * One draft to compose: the composer's fields plus the caller's two stamps.
 *
 * <p>Mirrors rs/mail/compose.rs {@code Draft} 37. The stamps are the
 * caller's so the bridge stays a pure function. A null component is an
 * absent key.
 *
 * @param from the sender, as the address alone
 * @param fromName the sender's display name, empty when there is none
 * @param to the recipients, comma separated
 * @param cc the carbon copies, comma separated
 * @param bcc the blind carbon copies, which reach the envelope and no header
 * @param subject the subject, as typed
 * @param body the text, as typed
 * @param markdown whether the text is Markdown, sent with its HTML beside it
 * @param date now, as an RFC 5322 date
 * @param messageId the {@code Message-ID} to stamp, angle brackets included
 * @param inReplyTo the {@code Message-ID} replied to, or empty
 * @param references the thread a reply continues, oldest first, space
 *     separated; empty outside a reply
 */
public record ComposeDraft(
        String from,
        String fromName,
        String to,
        String cc,
        String bcc,
        String subject,
        String body,
        boolean markdown,
        String date,
        String messageId,
        String inReplyTo,
        String references) {
    JSONObject toJson() {
        try {
            return new JSONObject()
                    .put("from", from)
                    .put("fromName", fromName)
                    .put("to", to)
                    .put("cc", cc)
                    .put("bcc", bcc)
                    .put("subject", subject)
                    .put("body", body)
                    .put("markdown", markdown)
                    .put("date", date)
                    .put("messageId", messageId)
                    .put("inReplyTo", inReplyTo)
                    .put("references", references);
        } catch (JSONException error) {
            throw new PimalayaException("Unwritable draft: " + error.getMessage());
        }
    }
}
