package org.pimalaya.account;

/**
 * OAuth 2.0 provider defaults: the app's clients, redirects, endpoints, scopes.
 *
 * <p>Redirects mirror the manifest intent-filters; base URLs come from
 * {@link org.pimalaya.client.AccountClient}. Google's is an Android client: no
 * secret (PKCE only), on the reversed-client-id scheme.
 */
public final class Oauth {
    /** The app's Google Android client, which has no secret. */
    public static final String GOOGLE_CLIENT_ID =
            "991810147220-4f2s8id0pksdj5vtj1ivgpadnoibl4g1.apps.googleusercontent.com";

    /** Reversed-client-id scheme, mirrored by the manifest intent-filter. */
    public static final String GOOGLE_REDIRECT_URI =
            "com.googleusercontent.apps.991810147220-4f2s8id0pksdj5vtj1ivgpadnoibl4g1:/oauth2redirect";

    /** Google's authorization endpoint. */
    public static final String GOOGLE_AUTH_ENDPOINT = "https://accounts.google.com/o/oauth2/v2/auth";
    /** Google's token endpoint. */
    public static final String GOOGLE_TOKEN_ENDPOINT = "https://oauth2.googleapis.com/token";

    /** CardDAV scope (the one DAVx5 uses); reuses the WebDAV transport. */
    public static final String GOOGLE_SCOPE = "https://www.googleapis.com/auth/carddav";

    /** People API scope, for the Google Contacts API backend. */
    public static final String GOOGLE_PEOPLE_SCOPE = "https://www.googleapis.com/auth/contacts";

    /** The Pimalaya Entra app registration (public client: PKCE, no secret). */
    public static final String MICROSOFT_CLIENT_ID = "ba9b19e1-973e-4d7c-aed9-848bd2fee385";

    /** Custom scheme, mirrored by the manifest intent-filter. */
    public static final String MICROSOFT_REDIRECT_URI = "pimalaya://oauth2redirect";

    /**
     * The dynamic-registration issuer flow's redirect, in the manifest too.
     *
     * <p>A reverse-DNS private-use scheme (RFC 8252 §7.1) with one slash,
     * having no naming authority. Fastmail rejects a loopback http redirect and
     * a dot-less scheme alike; Stalwart accepts this too.
     */
    public static final String REDIRECT_URI = "org.pimalaya:/oauth2redirect";

    /** The `common` tenant serves both personal (MSA) and Entra accounts. */
    public static final String MICROSOFT_AUTH_ENDPOINT =
            "https://login.microsoftonline.com/common/oauth2/v2.0/authorize";

    /** Entra's token endpoint, on the {@code common} tenant too. */
    public static final String MICROSOFT_TOKEN_ENDPOINT =
            "https://login.microsoftonline.com/common/oauth2/v2.0/token";

    /**
     * Graph contacts scope; offline_access makes Entra issue a refresh token.
     */
    public static final String MICROSOFT_SCOPE =
            "https://graph.microsoft.com/Contacts.ReadWrite offline_access";

    /** OpenID Connect scopes, which address no API. */
    private static final java.util.Set<String> OIDC_SCOPES =
            java.util.Set.of("offline_access", "openid", "profile", "email");

    /**
     * The hosts Google's client signs in to: Gmail IMAP/SMTP, its APIs and DAV.
     */
    static final java.util.Set<String> GOOGLE_HOSTS =
            java.util.Set.of(
                    "imap.gmail.com",
                    "smtp.gmail.com",
                    "www.googleapis.com",
                    "gmail.googleapis.com",
                    "people.googleapis.com",
                    "apidata.googleusercontent.com");

    /**
     * The scopes Google's own client asks for: mail, calendars and contacts.
     */
    private static final java.util.Set<String> GOOGLE_SCOPES =
            java.util.Set.of(
                    "https://mail.google.com/",
                    "https://www.googleapis.com/auth/calendar",
                    "https://www.googleapis.com/auth/carddav",
                    GOOGLE_PEOPLE_SCOPE);

    /**
     * The hosts Microsoft's client signs in to: Outlook IMAP/SMTP and Graph.
     */
    static final java.util.Set<String> MICROSOFT_HOSTS =
            java.util.Set.of(
                    "outlook.office365.com",
                    "outlook.office.com",
                    "smtp.office365.com",
                    "smtp-mail.outlook.com",
                    "graph.microsoft.com");

    /** The scopes Microsoft's own client asks for, Graph's bare or prefixed. */
    private static final java.util.Set<String> MICROSOFT_SCOPES =
            java.util.Set.of(
                    "offline_access",
                    "https://outlook.office.com/IMAP.AccessAsUser.All",
                    "https://outlook.office.com/SMTP.Send",
                    "Mail.ReadWrite",
                    "Mail.Send",
                    "Calendars.ReadWrite",
                    "Contacts.ReadWrite",
                    "https://graph.microsoft.com/Mail.ReadWrite",
                    "https://graph.microsoft.com/Mail.Send",
                    "https://graph.microsoft.com/Calendars.ReadWrite",
                    "https://graph.microsoft.com/Contacts.ReadWrite");

    /** Which of the app's own clients a grant runs with. */
    public enum Shipped {
        GOOGLE,
        MICROSOFT
    }

    private Oauth() {}

    /** Whether a grant runs against Google's authorization server. */
    static boolean isGoogle(String authorizationEndpoint) {
        return GOOGLE_AUTH_ENDPOINT.equals(authorizationEndpoint);
    }

    /**
     * The app's own client a grant may run with, or null for a generic one.
     *
     * <p>A discovered config may pair Google's sign-in with its own IMAP host,
     * so it signs in only when every endpoint the token goes to is the
     * provider's ({@link Endpoints#allOn}).
     */
    public static Shipped shipped(String authorizationEndpoint, java.util.Collection<String> endpoints) {
        if (isGoogle(authorizationEndpoint)
                && Endpoints.allOn(endpoints, "google", GOOGLE_HOSTS)) {
            return Shipped.GOOGLE;
        }
        if (MICROSOFT_AUTH_ENDPOINT.equals(authorizationEndpoint)
                && Endpoints.allOn(endpoints, "msgraph", MICROSOFT_HOSTS)) {
            return Shipped.MICROSOFT;
        }
        return null;
    }

    /**
     * A shipped client's scope: the requested one kept to the provider's set.
     */
    public static String fixedScope(Shipped client, String scope) {
        java.util.Set<String> allowed =
                client == Shipped.GOOGLE ? GOOGLE_SCOPES : MICROSOFT_SCOPES;
        java.util.Set<String> kept = new java.util.LinkedHashSet<>();
        for (String part : scopes(scope)) {
            if (allowed.contains(part)) {
                kept.add(part);
            }
        }
        return kept.isEmpty() ? null : String.join(" ", kept);
    }

    /**
     * What one grant's token is for; options sharing it share one grant.
     *
     * <p>Google's API set, else the RFC 8707 resource, else the scopes' APIs.
     * Entra refuses one token for two APIs (Outlook IMAP and Graph are two
     * consents); any Google scopes, Gmail's included, are one.
     */
    public static String audience(String authorizationEndpoint, String resource, String scope) {
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
    public static String union(String... lists) {
        java.util.Set<String> union = new java.util.LinkedHashSet<>();
        for (String list : lists) {
            union.addAll(scopes(list));
        }
        return union.isEmpty() ? null : String.join(" ", union);
    }

    /**
     * Whether a grant of {@code granted} scopes covers all {@code wanted} ones.
     *
     * <p>An unknown list on either side covers nothing.
     */
    static boolean covers(String granted, String wanted) {
        java.util.Set<String> want = scopes(wanted);
        return granted != null && !want.isEmpty() && scopes(granted).containsAll(want);
    }
}
