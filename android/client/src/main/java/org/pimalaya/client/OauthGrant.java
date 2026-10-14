package org.pimalaya.client;

/**
 * What renews an OAuth 2.0 grant beside its refresh token: where, for which
 * client, and for which scopes.
 *
 * @param tokenEndpoint the token endpoint the refresh runs against
 * @param clientId the OAuth client the tokens were issued to
 * @param clientSecret the client's secret, when its registration issued
 *     one (Google desktop-type clients require it in every exchange); null
 *     for none
 * @param scope the space-separated scopes the grant holds; null when
 *     unknown, which a refresh sends as none (the server's default)
 */
public record OauthGrant(String tokenEndpoint, String clientId, String clientSecret, String scope) {}
