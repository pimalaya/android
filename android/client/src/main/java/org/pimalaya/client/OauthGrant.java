package org.pimalaya.client;

/**
 * What renews an OAuth 2.0 grant beside its refresh token.
 *
 * @param tokenEndpoint the token endpoint the refresh runs against
 * @param clientId the OAuth client the tokens were issued to
 * @param clientSecret the client's secret, null for none (Google desktop-type
 *     clients require it in every exchange)
 * @param scope the space-separated scopes held; null when unknown, which a
 *     refresh sends as none (the server's default)
 */
public record OauthGrant(String tokenEndpoint, String clientId, String clientSecret, String scope) {}
