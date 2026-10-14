package org.pimalaya.client;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * One way a {@link ServiceConfig} accepts authentication
 * (io-pim-discovery {@code DiscoveryAuthMethod}). Only the fields
 * relevant to the {@link #type} are non-null.
 *
 * @param type the kind of method
 * @param authorizationEndpoint authorization endpoint (code grant only)
 * @param deviceAuthorizationEndpoint device authorization endpoint
 *     (device grant only)
 * @param tokenEndpoint token endpoint (both OAuth grants)
 * @param scope space-separated scopes to request (both OAuth grants)
 * @param issuer issuer URL ({@link Type#OAUTH_ISSUER} only)
 */
public record AuthMethod(
        Type type,
        String authorizationEndpoint,
        String deviceAuthorizationEndpoint,
        String tokenEndpoint,
        String scope,
        String issuer) {
    public enum Type {
        /** Username and password login (possibly an app password). */
        PASSWORD,
        /** Bearer token (RFC 6750), e.g. a provider-issued API token. */
        BEARER,
        /** OAuth 2.0 authorization code grant (RFC 6749). */
        OAUTH_AUTHORIZATION_CODE_GRANT,
        /** OAuth 2.0 device authorization grant (RFC 8628). */
        OAUTH_DEVICE_AUTHORIZATION_GRANT,
        /** OAuth 2.0 with grants to resolve from the issuer's RFC 8414 metadata. */
        OAUTH_ISSUER,
    }

    /** A username and password login. */
    public static AuthMethod password() {
        return new AuthMethod(Type.PASSWORD, null, null, null, null, null);
    }

    /** A bearer token. */
    public static AuthMethod bearer() {
        return new AuthMethod(Type.BEARER, null, null, null, null, null);
    }

    /**
     * An authorization code grant the caller already knows the endpoints of,
     * for the providers that publish no discoverable configuration.
     */
    public static AuthMethod codeGrant(
            String authorizationEndpoint, String tokenEndpoint, String scope) {
        return new AuthMethod(
                Type.OAUTH_AUTHORIZATION_CODE_GRANT,
                authorizationEndpoint,
                null,
                tokenEndpoint,
                scope,
                null);
    }

    /** A device authorization grant. */
    public static AuthMethod deviceGrant(
            String deviceAuthorizationEndpoint, String tokenEndpoint, String scope) {
        return new AuthMethod(
                Type.OAUTH_DEVICE_AUTHORIZATION_GRANT,
                null,
                deviceAuthorizationEndpoint,
                tokenEndpoint,
                scope,
                null);
    }

    /** OAuth with grants to resolve from the issuer's metadata. */
    public static AuthMethod issuer(String issuer) {
        return new AuthMethod(Type.OAUTH_ISSUER, null, null, null, null, issuer);
    }

    /**
     * Parses one method from the bridge's JSON shape: {@code "password"}
     * and {@code "bearer"} as plain strings, the OAuth variants as
     * single-key objects.
     */
    static AuthMethod from(Object json) {
        if ("bearer".equals(json)) {
            return bearer();
        }

        if (!(json instanceof JSONObject)) {
            return password();
        }

        JSONObject method = (JSONObject) json;
        try {
            if (method.has("oauthAuthorizationCodeGrant")) {
                JSONObject grant = method.getJSONObject("oauthAuthorizationCodeGrant");
                return codeGrant(
                        grant.getString("authorizationEndpoint"),
                        grant.getString("tokenEndpoint"),
                        grant.isNull("scope") ? null : grant.getString("scope"));
            }

            if (method.has("oauthDeviceAuthorizationGrant")) {
                JSONObject grant = method.getJSONObject("oauthDeviceAuthorizationGrant");
                return deviceGrant(
                        grant.getString("deviceAuthorizationEndpoint"),
                        grant.getString("tokenEndpoint"),
                        grant.isNull("scope") ? null : grant.getString("scope"));
            }

            if (method.has("oauthIssuer")) {
                return issuer(method.getString("oauthIssuer"));
            }
        } catch (JSONException error) {
            throw new PimalayaException("Unreadable auth method: " + error.getMessage());
        }

        throw new PimalayaException("Unreadable auth method: " + method);
    }
}
