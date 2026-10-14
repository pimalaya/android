package org.pimalaya.client;

/**
 * The client side of one OAuth 2.0 authorization code grant, as
 * {@link OauthSession} drives it.
 *
 * @param clientId the app's OAuth client
 * @param clientSecret the client's secret, null unless the registration
 *     issued one (Google desktop-type clients require it in the exchange)
 * @param redirectUri the app link, custom scheme or loopback URL the app
 *     intercepts
 * @param scope the scopes to ask for, usually from the matching
 *     {@link AuthMethod#scope}; null or empty when unknown
 */
public record OauthRequest(String clientId, String clientSecret, String redirectUri, String scope) {}
