package org.pimalaya.client;

import java.util.List;
import org.json.JSONObject;

/**
 * What one Microsoft Graph step brought (rs/mail/session.rs
 * {@code MailStepReply::Graph} 299).
 *
 * @param exhausted whether the step reached the end of the mailboxes
 * @param items the messages stepped
 */
public record GraphStepReply(boolean exhausted, List<GraphStepItem> items) {
    static GraphStepReply from(JSONObject reply) {
        return new GraphStepReply(
                reply.optBoolean("exhausted"), GraphStepItem.list(Json.array(reply, "items")));
    }
}
