package org.pimalaya.client;

import org.json.JSONObject;

/**
 * What an account base URL tells of its backend: facts, never its identity.
 *
 * <p>Mirrors rs/account.rs {@code AccountInfo} 257, read once per
 * {@link Account} ({@link Account#info}).
 *
 * @param accountLevel whether cards and mail are account-level, in m:n
 *     collections listed once per account
 * @param announcesCapabilities whether the session names the domains it
 *     serves (RFC 8620 2), read before probing one
 * @param appendsMail whether mail with no origin uploads (IMAP APPEND)
 * @param cardFetch how changed cards are read in bulk
 * @param cardPush how a round of card changes is pushed
 * @param hasAllMail whether mailboxes are labels over an All Mail holding
 *     every message
 * @param host the host the base URL reaches, an API by its name; the URL
 *     whole when it names none
 * @param keepsAnswered whether a reply's {@code \Answered} marker is kept
 * @param keepsDeleted whether a {@code \Deleted} marker is kept, so a delete
 *     needs no trash
 * @param mailStep how a mail step walks the account
 * @param namesResources whether a created card's resource is named by the
 *     client, {@code <id>.vcf}
 * @param protocol the protocol family a screen labels the account by
 * @param readsBackRevision whether a card write may answer no revision, read
 *     back instead
 * @param sessions how many mail sessions the account runs at once
 * @param submitsOverSession whether mail submits over the session it reads
 *     from
 */
public record BackendInfo(
        boolean accountLevel,
        boolean announcesCapabilities,
        boolean appendsMail,
        CardFetch cardFetch,
        CardPush cardPush,
        boolean hasAllMail,
        String host,
        boolean keepsAnswered,
        boolean keepsDeleted,
        MailStepKind mailStep,
        boolean namesResources,
        Protocol protocol,
        boolean readsBackRevision,
        int sessions,
        boolean submitsOverSession) {
    static BackendInfo from(JSONObject reply) {
        return new BackendInfo(
                reply.optBoolean("accountLevel"),
                reply.optBoolean("announcesCapabilities"),
                reply.optBoolean("appendsMail"),
                CardFetch.of(reply.optString("cardFetch")),
                CardPush.of(reply.optString("cardPush")),
                reply.optBoolean("hasAllMail"),
                Json.string(reply, "host"),
                reply.optBoolean("keepsAnswered"),
                reply.optBoolean("keepsDeleted"),
                MailStepKind.of(reply.optString("mailStep")),
                reply.optBoolean("namesResources"),
                Protocol.of(reply.optString("protocol")),
                reply.optBoolean("readsBackRevision"),
                Json.integer(reply, "sessions"),
                reply.optBoolean("submitsOverSession"));
    }
}
