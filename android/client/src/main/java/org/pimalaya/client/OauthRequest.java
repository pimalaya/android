package org.pimalaya.client;

/**
 * The client side of one OAuth 2.0 code grant, as {@link OauthSession} runs it.
 *
 * @param clientId the app's OAuth client
 * @param clientSecret the client's secret, null unless the registration
 *     issued one (Google desktop-type clients require it in the exchange)
 * @param redirectUri the app link, custom scheme or loopback URL intercepted
 * @param scope the scopes, usually {@link AuthMethod#scope}; null or empty
 *     when unknown
 */
public record OauthRequest(String clientId, String clientSecret, String redirectUri, String scope) {}
