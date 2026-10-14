package org.pimalaya.client;

/**
 * One RFC 7591 public client registration to ask for.
 *
 * @param endpoint the server's registration endpoint
 * @param redirectUri the redirect URI the client will use
 * @param clientName the client's name shown on consent screens, or null
 * @param scope the space-separated scopes the client will ask for, or null
 */
public record ClientRegistration(
        String endpoint, String redirectUri, String clientName, String scope) {}
