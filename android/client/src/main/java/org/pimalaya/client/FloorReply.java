package org.pimalaya.client;

import java.util.List;
import org.json.JSONObject;

/**
 * The floor of a mailbox's next chunk (a number of messages, never a span
 * of time), and the dates it was read from (rs/mail/listing.rs
 * {@code FloorReply} 257).
 *
 * @param floor the oldest {@code Date} among the {@code count} newest
 *     messages dated before the ceiling; null when fewer than {@code count}
 *     dated messages lie below it: the mailbox is whole below it
 * @param dated how many dated messages were taken
 * @param dates their {@code Date}s, newest first: the floor the last of
 *     them when there are {@code count}, fewer when the mailbox is whole
 *     below the ceiling
 */
public record FloorReply(String floor, int dated, List<String> dates) {
    static FloorReply from(JSONObject reply) {
        return new FloorReply(
                reply.isNull("floor") ? null : reply.optString("floor", null),
                Json.integer(reply, "dated"),
                Json.strings(reply.optJSONArray("dates")));
    }
}
