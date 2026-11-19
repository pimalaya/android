package org.pimalaya.client;

import java.util.List;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * What one Gmail step is asked.
 *
 * <p>Mirrors rs/mail/session.rs {@code MailStepRequest}, which reads
 * {@code count} alone.
 *
 * @param mailbox the one mailbox a filtered view steps, null for the account;
 *     handed to the bridge beside the request, not in it
 * @param count how many messages the step brings
 * @param frontier the RFC 3339 instant the step starts below, null (JSON
 *     null) for none
 * @param anchors the anchors the last JMAP step left, oldest first
 * @param mailboxes the mailboxes stepped, by name; null for an absent key
 */
public record StepRequest(
        String mailbox,
        int count,
        String frontier,
        List<String> anchors,
        List<String> mailboxes) {
    JSONObject toJson() {
        try {
            JSONObject request =
                    new JSONObject()
                            .put("count", count)
                            .put("frontier", frontier == null ? JSONObject.NULL : frontier)
                            .put("anchors", new JSONArray(anchors));
            if (mailboxes != null) {
                request.put("mailboxes", new JSONArray(mailboxes));
            }
            return request;
        } catch (JSONException error) {
            throw new PimalayaException("Unwritable step request: " + error.getMessage());
        }
    }
}
