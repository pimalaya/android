package org.pimalaya;

/**
 * OAuth 2.0 provider defaults for the connection flow: the app's
 * client registrations, their redirect URIs (mirrored by the manifest
 * intent-filters) and the provider endpoints and scopes. The account
 * base URLs derived from these choices come from the bridge (the
 * {@code *Base} helpers on
 * {@link org.pimalaya.client.PimalayaClient}).
 *
 * <p>The endpoints and scopes belong in pimconf eventually (so every
 * Pimalaya app shares one set of provider rules). The Google client is
 * an Android OAuth client: no secret (PKCE only), and a redirect on
 * the reversed-client-id custom scheme the OS routes back via the
 * manifest intent-filter.
 */
final class Oauth {
    static final String GOOGLE_CLIENT_ID =
            "991810147220-4f2s8id0pksdj5vtj1ivgpadnoibl4g1.apps.googleusercontent.com";

    /** Reversed-client-id scheme, mirrored by the manifest intent-filter. */
    static final String GOOGLE_REDIRECT_URI =
            "com.googleusercontent.apps.991810147220-4f2s8id0pksdj5vtj1ivgpadnoibl4g1:/oauth2redirect";

    static final String GOOGLE_AUTH_ENDPOINT = "https://accounts.google.com/o/oauth2/v2/auth";
    static final String GOOGLE_TOKEN_ENDPOINT = "https://oauth2.googleapis.com/token";

    /** CardDAV scope (the one DAVx5 uses); reuses the WebDAV transport. */
    static final String GOOGLE_SCOPE = "https://www.googleapis.com/auth/carddav";

    /** People API scope, for the Google Contacts API backend. */
    static final String GOOGLE_PEOPLE_SCOPE = "https://www.googleapis.com/auth/contacts";

    /**
     * The Pimalaya Entra app registration (a public client: no secret,
     * PKCE only, like the Google one).
     */
    static final String MICROSOFT_CLIENT_ID = "ba9b19e1-973e-4d7c-aed9-848bd2fee385";

    /** Custom scheme, mirrored by the manifest intent-filter. */
    static final String MICROSOFT_REDIRECT_URI = "pimalaya://oauth2redirect";

    /**
     * The redirect of the dynamic-registration issuer flow: a
     * reverse-DNS private-use scheme (RFC 8252 §7.1), mirrored by the
     * manifest intent-filter. Fastmail's registration rejects a
     * loopback http redirect and a bare (dot-less) scheme alike, and
     * accepts this reverse-DNS form; Stalwart accepts it too. Single
     * slash per RFC 8252 §7.1: a private-use scheme has no naming
     * authority, so no {@code //} component.
     */
    static final String REDIRECT_URI = "org.pimalaya:/oauth2redirect";

    /** The `common` tenant serves both personal (MSA) and Entra accounts. */
    static final String MICROSOFT_AUTH_ENDPOINT =
            "https://login.microsoftonline.com/common/oauth2/v2.0/authorize";

    static final String MICROSOFT_TOKEN_ENDPOINT =
            "https://login.microsoftonline.com/common/oauth2/v2.0/token";

    /** Graph contacts scope; offline_access makes Entra issue a refresh token. */
    static final String MICROSOFT_SCOPE =
            "https://graph.microsoft.com/Contacts.ReadWrite offline_access";

    /** OpenID Connect scopes, which address no API. */
    private static final java.util.Set<String> OIDC_SCOPES =
            java.util.Set.of("offline_access", "openid", "profile", "email");

    private Oauth() {}

    /** Whether a grant runs against Google's authorization server. */
    static boolean isGoogle(String authorizationEndpoint) {
        return GOOGLE_AUTH_ENDPOINT.equals(authorizationEndpoint);
    }

    /**
     * What one grant's token is for: Google's whole set of APIs, else the
     * RFC 8707 resource it names, else the APIs its scopes address.
     *
     * <p>Two options of one authorization server share a grant only when
     * this agrees. Entra refuses one token for two APIs, so Microsoft mail
     * over IMAP (Outlook scopes) and calendars (Graph scopes) are two
     * consents, a bare Graph scope ({@code Calendars.ReadWrite}) being
     * Graph's, while Graph mail and Graph calendars are one. Google issues
     * one token for every API it serves, Gmail's restricted
     * {@code https://mail.google.com/} included, so every Google scope is
     * one audience and an address ticking mail, calendars and contacts is
     * one consent.
     */
    static String audience(String authorizationEndpoint, String resource, String scope) {
        if (isGoogle(authorizationEndpoint)) {
            return GOOGLE_AUTH_ENDPOINT;
        }
        if (resource != null) {
            return resource;
        }
        if (scope == null) {
            return "";
        }

        java.util.Set<String> apis = new java.util.TreeSet<>();
        for (String part : scopes(scope)) {
            if (OIDC_SCOPES.contains(part)) {
                continue;
            }
            int scheme = part.indexOf("://");
            if (scheme < 0) {
                apis.add("https://graph.microsoft.com");
                continue;
            }
            int path = part.indexOf('/', scheme + 3);
            apis.add(path < 0 ? part : part.substring(0, path));
        }
        return String.join(" ", apis);
    }

    /** The scopes of a space-separated list, in order, each once. */
    static java.util.Set<String> scopes(String scope) {
        java.util.Set<String> scopes = new java.util.LinkedHashSet<>();
        if (scope == null) {
            return scopes;
        }
        for (String part : scope.trim().split("\\s+")) {
            if (!part.isEmpty()) {
                scopes.add(part);
            }
        }
        return scopes;
    }

    /** The union of space-separated scope lists, in order; null when empty. */
    static String union(String... lists) {
        java.util.Set<String> union = new java.util.LinkedHashSet<>();
        for (String list : lists) {
            union.addAll(scopes(list));
        }
        return union.isEmpty() ? null : String.join(" ", union);
    }

    /**
     * Whether a grant of the {@code granted} scopes covers every one of
     * the {@code wanted} ones. An unknown list on either side covers
     * nothing: a grant whose reach was never recorded is not assumed.
     */
    static boolean covers(String granted, String wanted) {
        java.util.Set<String> want = scopes(wanted);
        return granted != null && !want.isEmpty() && scopes(granted).containsAll(want);
    }
}
