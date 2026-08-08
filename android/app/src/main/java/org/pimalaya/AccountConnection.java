package org.pimalaya;

import org.pimalaya.client.Account;

/**
 * One domain's half of an account: where that domain lives and how to sign in
 * to it.
 *
 * <p>An account is one identity; this is what it holds per domain. They are
 * separate because a provider is under no obligation to serve mail, contacts
 * and calendars from one endpoint or accept one credential for all three, and
 * plenty do neither. They are <em>not</em> separate accounts, because the
 * person has one account: an expired token is one repair, and a contact card
 * has to be relatable to the sender of a message without reassembling an
 * identity that onboarding took apart.
 *
 * <p>A JMAP provider is the case that settles it: RFC 8620 has one session
 * resource behind one authentication, and the capabilities each account
 * advertises say which domains it serves. Three connections there are three
 * copies of the same endpoint and the same token, which is exactly what one
 * account with several domains is meant to express.
 */
final class AccountConnection {
    /** Where the domain lives, and the credential presented to it. */
    final Account account;

    /** Refresh token of an OAuth connection; null for password ones. */
    final String refreshToken;

    /** Token endpoint the refresh runs against; null without a refresh token. */
    final String tokenEndpoint;

    /** OAuth client the tokens were issued to; null without a refresh token. */
    final String clientId;

    /**
     * Secret of the OAuth client, when its registration issued one (Google
     * desktop-type clients require it in every exchange); null for secret-less
     * clients and password connections.
     */
    final String clientSecret;

    AccountConnection(Account account) {
        this(account, null, null, null, null);
    }

    AccountConnection(
            Account account,
            String refreshToken,
            String tokenEndpoint,
            String clientId,
            String clientSecret) {
        this.account = account;
        this.refreshToken = refreshToken;
        this.tokenEndpoint = tokenEndpoint;
        this.clientId = clientId;
        this.clientSecret = clientSecret;
    }

    /** The same connection with a refreshed access token. */
    AccountConnection withAccessToken(String accessToken, String refreshed) {
        return new AccountConnection(
                new Account(account.baseUrl, "", accessToken),
                refreshed,
                tokenEndpoint,
                clientId,
                clientSecret);
    }
}
