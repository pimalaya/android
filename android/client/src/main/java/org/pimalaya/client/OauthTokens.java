package org.pimalaya.client;

import org.json.JSONObject;

/**
 * Tokens returned by an OAuth 2.0 code exchange or refresh (io-oauth
 * {@code Oauth20AccessTokenSuccessParams}, RFC 6749 section 5.1). To use
 * the access token against CardDAV, build the {@link Account} with an
 * empty login and the token as password: the bridge then authenticates
 * with Bearer instead of Basic.
 *
 * @param accessToken the issued access token
 * @param tokenType token type, {@code Bearer} in practice
 * @param expiresIn lifetime in seconds; 0 when the server omitted it
 * @param refreshToken refresh token; null when the server issued none
 * @param scope granted scopes; null when identical to the requested ones
 * @param issuedAt Unix epoch seconds when issued (server clock); 0 when
 *     unknown
 */
public record OauthTokens(
        String accessToken,
        String tokenType,
        long expiresIn,
        String refreshToken,
        String scope,
        long issuedAt) {
    /** Parses the bridge's RFC 6749 §5.1 success params JSON. */
    static OauthTokens from(JSONObject json) {
        return new OauthTokens(
                json.optString("access_token"),
                json.optString("token_type", "Bearer"),
                json.optLong("expires_in", 0),
                json.isNull("refresh_token") ? null : json.optString("refresh_token"),
                json.isNull("scope") ? null : json.optString("scope"),
                json.optLong("issued_at", 0));
    }
}
