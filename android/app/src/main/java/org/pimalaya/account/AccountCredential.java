package org.pimalaya.account;

import java.util.UUID;
import org.pimalaya.client.OauthGrant;
import org.pimalaya.client.OauthTokens;

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
 * several: Microsoft issues Outlook's scopes through a separate grant
 * from Graph's, so those are two credentials of one identity, and
 * renewing either must leave the other alone. Google issues one token
 * for all of its APIs, so a Google account holds one: a domain added
 * later asks for the union and its grant replaces the earlier one.
 */
public final class AccountCredential {
    /**
     * What renews an OAuth credential without asking the user.
     *
     * @param refreshToken the refresh token; null when the server issued
     *     none
     * @param grant where and for which client the refresh runs, and the
     *     scopes the grant holds, as the token response named them (null
     *     for a grant stored before the scopes were kept)
     */
    public record Renewal(String refreshToken, OauthGrant grant) {}

    /**
     * What names this credential inside its account. Opaque and minted
     * per grant: two sign-ins are two credentials even against one
     * provider, since the second cannot renew the first.
     */
    public final String id;

    /** The login presented with the secret; empty for a bearer token. */
    public final String login;

    /** The password, API token or OAuth access token itself. */
    public final String secret;

    /** How an OAuth credential renews; null for a password one. */
    public final Renewal renewal;

    AccountCredential(String id, String login, String secret, Renewal renewal) {
        this.id = id;
        this.login = login;
        this.secret = secret;
        this.renewal = renewal;
    }

    /** A credential signed in with a login and a secret. */
    public static AccountCredential password(String login, String secret) {
        return new AccountCredential(newId(), login, secret, null);
    }

    /** A credential a browser grant produced, renewable on its own. */
    public static AccountCredential oauth(OauthTokens tokens, OauthGrant grant) {
        return new AccountCredential(
                newId(), "", tokens.accessToken(), new Renewal(tokens.refreshToken(), grant));
    }

    /** The same credential, renewed. Same id: it is the same consent. */
    AccountCredential withTokens(String accessToken, String refreshToken) {
        return new AccountCredential(
                id, login, accessToken, new Renewal(refreshToken, renewal.grant()));
    }

    /** Refresh token of an OAuth credential; null for a password one. */
    public String refreshToken() {
        return renewal == null ? null : renewal.refreshToken();
    }

    /** Whether this credential can be renewed without asking the user. */
    public boolean renewable() {
        return refreshToken() != null;
    }

    /** A fresh identity for a credential a new sign-in just produced. */
    static String newId() {
        return UUID.randomUUID().toString();
    }
}
