package org.pimalaya.account;

import java.util.UUID;
import org.pimalaya.client.OauthGrant;
import org.pimalaya.client.OauthTokens;

/**
 * What one sign-in produced: the secret presented, and what renews it.
 *
 * <p>Stored once per consent, named by every domain it covers: a rotating
 * refresh token (Fastmail) retires other copies on first use. Outlook and Graph
 * grants are two credentials; Google's one token covers every API.
 */
public final class AccountCredential {
    /**
     * What renews an OAuth credential without asking the user.
     *
     * @param refreshToken the refresh token; null when the server issued none
     * @param grant endpoint, client and granted scopes (null on old grants)
     */
    public record Renewal(String refreshToken, OauthGrant grant) {}

    /**
     * What names this credential inside its account.
     *
     * <p>Opaque and minted per grant: two sign-ins are two credentials even
     * against one provider, since the second cannot renew the first.
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
