package org.pimalaya.client;

import java.util.List;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * What one mail step is asked (rs/mail/session.rs {@code MailStepRequest}
 * 244), each backend reading what it needs, and the mailbox it steps.
 *
 * @param mailbox the one mailbox a view filtered to it steps, by name; null
 *     for the account. Handed to the bridge beside the request, not in it
 * @param count how many messages the step brings
 * @param frontier the instant (RFC 3339) the step starts below, null for
 *     none (written as JSON null)
 * @param bound the account's bound, null for none (written as JSON null)
 * @param anchors the anchors the last JMAP step left, oldest first
 * @param mailboxes the mailboxes stepped, by name; null for an absent key
 */
public record StepRequest(
        String mailbox,
        int count,
        String frontier,
        String bound,
        List<String> anchors,
        List<String> mailboxes) {
    JSONObject toJson() {
        try {
            JSONObject request =
                    new JSONObject()
                            .put("count", count)
                            .put("frontier", frontier == null ? JSONObject.NULL : frontier)
                            .put("bound", bound == null ? JSONObject.NULL : bound)
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
