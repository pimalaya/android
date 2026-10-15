package org.pimalaya.client;

import org.json.JSONObject;

/**
 * What an account base URL tells about its backend.
 *
 * <p>Mirrors rs/ffi/account.rs {@code AccountInfoReply} 692. A flag the reply
 * leaves out reads false.
 *
 * @param accountLevel whether cards are account-level with m:n book
 *     memberships (JMAP, Google)
 * @param backend the backend behind the URL
 * @param submitsOverSession whether mail submits over the session it reads
 *     from (JMAP, Graph, Gmail)
 */
public record BackendInfo(boolean accountLevel, Backend backend, boolean submitsOverSession) {
    static BackendInfo from(JSONObject reply) {
        return new BackendInfo(
                reply.optBoolean("accountLevel"),
                Backend.of(reply.optString("backend")),
                reply.optBoolean("submitsOverSession"));
    }
}
