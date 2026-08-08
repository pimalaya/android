package org.pimalaya.client;

import org.json.JSONObject;

/**
 * The subset of an authorization server's RFC 8414 metadata the
 * onboarding needs: the endpoints to drive the code grant, and the
 * RFC 7591 registration endpoint when the server lets a public client
 * register itself (null otherwise, meaning a pre-registered client id
 * is required).
 */
public final class ServerMetadata {
    public final String issuer;
    public final String authorizationEndpoint;
    public final String tokenEndpoint;

    /** RFC 7591 registration endpoint, or null when unsupported. */
    public final String registrationEndpoint;

    /** Space-separated supported scopes, empty when unadvertised. */
    public final String scopesSupported;

    ServerMetadata(JSONObject json) {
        this.issuer = json.optString("issuer", null);
        this.authorizationEndpoint = json.optString("authorization_endpoint", null);
        this.tokenEndpoint = json.optString("token_endpoint", null);
        this.registrationEndpoint =
                json.isNull("registration_endpoint")
                        ? null
                        : json.optString("registration_endpoint", null);

        StringBuilder scopes = new StringBuilder();
        org.json.JSONArray supported = json.optJSONArray("scopes_supported");
        for (int index = 0; supported != null && index < supported.length(); index++) {
            if (scopes.length() > 0) {
                scopes.append(' ');
            }
            scopes.append(supported.optString(index));
        }
        this.scopesSupported = scopes.toString();
    }

    /** Whether a public client can register itself here (RFC 7591). */
    public boolean supportsDynamicRegistration() {
        return registrationEndpoint != null && !registrationEndpoint.isEmpty();
    }

    /**
     * The scope a contacts client should request here, negotiated by
     * the bridge against {@link #scopesSupported} (the standard
     * contacts scope plus {@code offline_access}, each kept only when
     * advertised).
     */
    /**
     * The scope to request for the domains one grant covers, negotiated
     * against what this server advertises.
     *
     * <p>Space-separated domain ids ({@code mail}, {@code contacts},
     * {@code calendars}): a grant shared by several asks for all of theirs, and
     * asking for the wrong one does not fail loudly, it just puts the wrong
     * thing on the consent screen.
     */
    public String domainScope(String domains) {
        return PimalayaClient.string(
                PimalayaClient.object(Native.oauthDomainScope(scopesSupported, domains)), "scope");
    }
}
