package org.pimalaya.client;

import java.util.List;
import org.json.JSONObject;

/**
 * What the store files of one message, read once by the bridge
 * (rs/mail/read.rs {@code Description} 206). No body crosses the boundary.
 *
 * @param attachments its attachments as {@link MailClient#parseMessage}
 *     lists them; null when the parser cannot read it
 * @param hash the content hash the object store names it by
 *     ({@code sha256-128})
 * @param size its size in octets
 * @param summary the summary io-pimdir derives from it, opaque (the pimdir
 *     persistence format), read-only by contract; null where it yields none
 */
public record Description(
        List<MessageBody.Attachment> attachments, String hash, long size, JSONObject summary) {
    static Description from(JSONObject reply) {
        return new Description(
                reply.isNull("attachments")
                        ? null
                        : MessageBody.Attachment.list(reply.optJSONArray("attachments")),
                Json.string(reply, "hash"),
                Json.longInteger(reply, "size"),
                reply.optJSONObject("summary"));
    }
}
