package org.pimalaya.client;

import org.json.JSONObject;

/**
 * What an account base URL tells about its backend (rs/ffi/account.rs
 * {@code AccountInfoReply} 727). A flag the reply leaves out reads false.
 *
 * @param accountLevel whether cards are account-level resources with m:n
 *     addressbook memberships (JMAP, Google)
 * @param backend the backend behind the URL
 * @param submitsOverSession whether mail submits through the session it
 *     reads from (JMAP, Graph, Gmail)
 * @param writesEvents whether the calendar behind the URL takes event
 *     writes at all
 * @param writesExdates whether a calendar write carries an {@code EXDATE}
 * @param writesOverrides whether a calendar write carries an override of
 *     one occurrence
 */
public record BackendInfo(
        boolean accountLevel,
        Backend backend,
        boolean submitsOverSession,
        boolean writesEvents,
        boolean writesExdates,
        boolean writesOverrides) {
    static BackendInfo from(JSONObject reply) {
        return new BackendInfo(
                reply.optBoolean("accountLevel"),
                Backend.of(reply.optString("backend")),
                reply.optBoolean("submitsOverSession"),
                reply.optBoolean("writesEvents"),
                reply.optBoolean("writesExdates"),
                reply.optBoolean("writesOverrides"));
    }
}
