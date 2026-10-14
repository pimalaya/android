package org.pimalaya.client;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * The subset of an authorization server's RFC 8414 metadata onboarding needs.
 *
 * @param issuer the issuer, or null
 * @param authorizationEndpoint the authorization endpoint, or null
 * @param tokenEndpoint the token endpoint, or null
 * @param registrationEndpoint RFC 7591 registration endpoint; null when a
 *     public client cannot register, so a pre-registered client id is needed
 * @param scopesSupported the advertised scopes, space-separated; empty if none
 */
public record ServerMetadata(
        String issuer,
        String authorizationEndpoint,
        String tokenEndpoint,
        String registrationEndpoint,
        String scopesSupported) {
    static ServerMetadata from(JSONObject json) {
        StringBuilder scopes = new StringBuilder();
        JSONArray supported = json.optJSONArray("scopes_supported");
        for (int index = 0; supported != null && index < supported.length(); index++) {
            if (scopes.length() > 0) {
                scopes.append(' ');
            }
            scopes.append(supported.optString(index));
        }

        return new ServerMetadata(
                json.optString("issuer", null),
                json.optString("authorization_endpoint", null),
                json.optString("token_endpoint", null),
                json.isNull("registration_endpoint")
                        ? null
                        : json.optString("registration_endpoint", null),
                scopes.toString());
    }

    /** Whether a public client can register itself here (RFC 7591). */
    public boolean supportsDynamicRegistration() {
        return registrationEndpoint != null && !registrationEndpoint.isEmpty();
    }

    /**
     * The scope for the domains one grant covers, negotiated by the bridge.
     *
     * <p>{@code domains}: space-separated ids ({@code mail}, {@code contacts},
     * {@code calendars}). A wrong one does not fail, it shows the wrong thing
     * on the consent screen.
     */
    public String domainScope(String domains) {
        return Json.string(
                Replies.object(Native.oauthDomainScope(scopesSupported, domains)), "scope");
    }
}
