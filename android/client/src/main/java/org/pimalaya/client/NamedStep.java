package org.pimalaya.client;

import java.util.List;
import org.json.JSONObject;

/**
 * What one Gmail or JMAP step brought.
 *
 * <p>Mirrors rs/mail/session.rs {@code MailStepReply::Named} 285.
 *
 * @param anchors the anchors the next step starts from, JMAP; null for Gmail
 * @param byReception whether the step walked by reception, not {@code Date}
 * @param exhausted whether the step reached the end of the account
 * @param historyId the account's state read before the step
 * @param items the messages stepped
 */
public record NamedStep(
        List<String> anchors,
        boolean byReception,
        boolean exhausted,
        String historyId,
        List<NamedStepItem> items) {
    static NamedStep from(JSONObject reply) {
        return new NamedStep(
                reply.optJSONArray("anchors") == null
                        ? null
                        : Json.strings(reply.optJSONArray("anchors")),
                reply.optBoolean("byReception"),
                reply.optBoolean("exhausted"),
                Json.string(reply, "historyId"),
                NamedStepItem.list(Json.array(reply, "items")));
    }
}
