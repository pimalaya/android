package org.pimalaya.client;

import java.util.Map;
import org.json.JSONObject;

/**
 * One OAuth 2.0 authorization code grant with PKCE.
 *
 * <p>Holds the bridge's CSRF state and PKCE verifier from
 * {@link #authorizeUrl} to {@link #redeem}, which must match. {@link #redeem}
 * blocks: run it off the main thread.
 */
public final class OauthSession {
    private final String clientId;
    private final String clientSecret;
    private final String redirectUri;
    private final String scope;
    private final String state;
    private final String pkceVerifier;

    /** Prepares a grant, its state and verifier fresh from the bridge. */
    public static OauthSession start(OauthRequest request) {
        JSONObject params = Replies.object(Native.oauthSessionParams());
        return new OauthSession(
                request, Json.string(params, "state"), Json.string(params, "verifier"));
    }

    /**
     * Rebuilds a grant from persisted state and PKCE verifier.
     *
     * <p>Redeems a redirect after the process died while the user was in the
     * browser. The scope only matters before the request, so it may be empty.
     */
    public static OauthSession resume(OauthRequest request, String state, String pkceVerifier) {
        return new OauthSession(request, state, pkceVerifier);
    }

    private OauthSession(OauthRequest request, String state, String pkceVerifier) {
        this.clientId = request.clientId();
        this.clientSecret = request.clientSecret() == null ? "" : request.clientSecret();
        this.redirectUri = request.redirectUri();
        this.scope = request.scope() == null ? "" : request.scope();
        this.state = state;
        this.pkceVerifier = pkceVerifier;
    }

    /** The scopes the authorization request asks for; empty when unknown. */
    public String scope() {
        return scope;
    }

    /** The CSRF state of the authorization request, for persistence. */
    public String state() {
        return state;
    }

    /** The PKCE verifier of the authorization request, for persistence. */
    public String pkceVerifier() {
        return pkceVerifier;
    }

    /**
     * Builds the authorization URL to open in the user's browser.
     *
     * <p>{@code extras} (nullable) adds provider query parameters, e.g.
     * Google's {@code access_type=offline} and {@code prompt=consent}, without
     * which no refresh token is issued.
     */
    public String authorizeUrl(String authorizationEndpoint, Map<String, String> extras) {
        JSONObject reply =
                Replies.object(
                        Native.oauthAuthorizeUrl(
                                authorizationEndpoint,
                                clientId,
                                redirectUri,
                                scope,
                                state,
                                pkceVerifier,
                                extras == null ? "" : new JSONObject(extras).toString()));
        return Json.string(reply, "url");
    }

    /** Checks the redirect's CSRF state and exchanges its code for tokens. */
    public OauthTokens redeem(String tokenEndpoint, String redirectUrl) {
        return redeem(tokenEndpoint, redirectUrl, null);
    }

    /**
     * {@link #redeem(String, String)}, also refusing a foreign RFC 9207 iss.
     *
     * <p>A redirect whose {@code iss} is not {@code issuer} (null if unknown)
     * is the mix-up attack: a server bouncing the browser so the code lands at
     * its own token endpoint.
     */
    public OauthTokens redeem(String tokenEndpoint, String redirectUrl, String issuer) {
        JSONObject validated =
                Replies.object(
                        Native.oauthValidateRedirect(
                                redirectUrl, state, issuer == null ? "" : issuer));
        String code = Json.string(validated, "code");

        Transport transport = new Transport();
        try {
            JSONObject reply =
                    Replies.object(
                            Native.oauthRequestAccessToken(
                                    transport,
                                    tokenEndpoint,
                                    clientId,
                                    clientSecret,
                                    code,
                                    redirectUri,
                                    pkceVerifier));
            return OauthTokens.from(reply);
        } finally {
            transport.close();
        }
    }
}
