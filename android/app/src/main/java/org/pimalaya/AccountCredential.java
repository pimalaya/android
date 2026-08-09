package org.pimalaya;

import java.util.UUID;

/**
 * What one sign-in produced: the secret an account presents, and
 * everything needed to renew it.
 *
 * <p>Stored once per <em>consent</em> and named by every domain it
 * covers, which is the whole point of it being its own object. A
 * browser grant covers whichever domains chose the same authorization
 * server, and the tokens it returns belong to all of them; keeping a
 * copy per domain meant a provider that issues a fresh refresh token on
 * every use (Fastmail, among others) retired the other copies the
 * moment one was spent, and the domains still holding them could never
 * sign in again. One object, one refresh, and there is nothing left to
 * keep in step.
 *
 * <p>Per consent and not per account, because one account can hold
 * several: Google issues its restricted Gmail scopes through a separate
 * grant from its contacts ones, so those are two credentials of one
 * identity, and renewing either must leave the other alone.
 */
final class AccountCredential {
    /**
     * What names this credential inside its account. Opaque and minted
     * per grant: two sign-ins are two credentials even against one
     * provider, since the second cannot renew the first.
     */
    final String id;

    /** The login presented with the secret; empty for a bearer token. */
    final String login;

    /** The password, API token or OAuth access token itself. */
    final String secret;

    /** Refresh token of an OAuth credential; null for a password one. */
    final String refreshToken;

    /** Token endpoint the refresh runs against; null without a refresh token. */
    final String tokenEndpoint;

    /** OAuth client the tokens were issued to; null without a refresh token. */
    final String clientId;

    /**
     * Secret of the OAuth client, when its registration issued one (Google
     * desktop-type clients require it in every exchange); null for
     * secret-less clients and password credentials.
     */
    final String clientSecret;

    AccountCredential(
            String id,
            String login,
            String secret,
            String refreshToken,
            String tokenEndpoint,
            String clientId,
            String clientSecret) {
        this.id = id;
        this.login = login;
        this.secret = secret;
        this.refreshToken = refreshToken;
        this.tokenEndpoint = tokenEndpoint;
        this.clientId = clientId;
        this.clientSecret = clientSecret;
    }

    /** A credential signed in with a login and a secret. */
    static AccountCredential password(String login, String secret) {
        return new AccountCredential(newId(), login, secret, null, null, null, null);
    }

    /** A credential a browser grant produced, renewable on its own. */
    static AccountCredential oauth(
            String accessToken,
            String refreshToken,
            String tokenEndpoint,
            String clientId,
            String clientSecret) {
        return new AccountCredential(
                newId(), "", accessToken, refreshToken, tokenEndpoint, clientId, clientSecret);
    }

    /** The same credential, renewed. Same id: it is the same consent. */
    AccountCredential withTokens(String accessToken, String refreshToken) {
        return new AccountCredential(
                id, login, accessToken, refreshToken, tokenEndpoint, clientId, clientSecret);
    }

    /** Whether this credential can be renewed without asking the user. */
    boolean renewable() {
        return refreshToken != null;
    }

    /** A fresh identity for a credential a new sign-in just produced. */
    static String newId() {
        return UUID.randomUUID().toString();
    }
}
