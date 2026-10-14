package org.pimalaya.client;

import org.json.JSONObject;

/**
 * A mail session the bridge opened (rs/ffi/mail.rs {@code OpenedReply} 725).
 *
 * @param expungesOne whether a delete for good erases one message alone; true
 *     where the reply says nothing
 * @param handle the handle a bridge reply names
 */
record Opened(boolean expungesOne, long handle) {
    static Opened from(JSONObject reply) {
        return new Opened(reply.optBoolean("expungesOne", true), reply.optLong("handle"));
    }
}
