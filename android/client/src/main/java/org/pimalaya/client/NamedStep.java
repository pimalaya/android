package org.pimalaya.client;

import java.util.List;
import org.json.JSONObject;

/**
 * What one Gmail step brought.
 *
 * <p>Mirrors rs/mail/session.rs {@code MailStepReply}.
 *
 * @param anchors the anchors the next step starts from; null, as Gmail
 *     answers none
 * @param byReception whether the step walked by reception, not {@code Date}
 * @param exhausted whether the step reached the end of the account
 * @param historyId the account's history id read before the step
 * @param items the messages stepped, each filed in one mailbox at least
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
