package org.pimalaya.account;

import java.net.URI;
import java.util.Collection;
import java.util.Locale;
import java.util.Set;
import org.pimalaya.client.PimalayaClient;

/**
 * Where the connection flow lets a credential go, and how it names that place
 * to the user.
 *
 * <p>One rule: a credential goes out only over TLS. An endpoint is
 * {@code https}, {@code imaps} or {@code smtps}, or {@code smtp}, which the
 * bridge always upgrades with STARTTLS before signing in; plain {@code http}
 * and {@code imap} are refused here, before the bridge refuses them again.
 * The internal account markers ({@code google://}, {@code msgraph://},
 * {@code jmap://}) stand for https endpoints the bridge builds itself.
 */
public final class Endpoints {
    private static final Set<String> TLS = Set.of("https", "imaps", "smtps", "smtp");

    private static final Set<String> MARKERS = Set.of("google", "msgraph", "jmap");

    private Endpoints() {}

    /** Whether a credential may go to this endpoint. */
    static boolean secure(String url) {
        String scheme = scheme(url);
        return scheme != null && (TLS.contains(scheme) || MARKERS.contains(scheme));
    }

    /**
     * What was typed, as the URL the domain's client connects to: implicit-TLS
     * IMAP on its default port for mail, https for a DAV collection; null when
     * the typed URL names a scheme the domain cannot sign in over safely
     * ({@code imap://}, {@code http://}, anything else).
     */
    public static String manualUrl(PimDomain domain, String entered) {
        entered = entered.trim();
        if (domain != PimDomain.MAIL) {
            String url = entered.contains("://") ? entered : "https://" + entered;
            return "https".equals(scheme(url)) && host(url) != null ? url : null;
        }
        if (entered.contains("://")) {
            return "imaps".equals(scheme(entered)) && host(entered) != null ? entered : null;
        }
        String url = entered.contains(":") ? "imaps://" + entered : "imaps://" + entered + ":993";
        return host(url) != null ? url : null;
    }

    /**
     * What was typed for a submission server, as the endpoint it opens, or
     * null when it names neither {@code smtps} nor {@code smtp}.
     */
    public static String submitUrl(String entered) {
        String url = PimalayaClient.submitUrl(entered.trim());
        String scheme = scheme(url);
        boolean smtp = "smtps".equals(scheme) || "smtp".equals(scheme);
        return smtp && host(url) != null ? url : null;
    }

    /** The scheme of an endpoint, lowercased; null when it has none. */
    public static String scheme(String url) {
        if (url == null) {
            return null;
        }
        int end = url.indexOf("://");
        return end <= 0 ? null : url.substring(0, end).toLowerCase(Locale.ROOT);
    }

    /**
     * The host an endpoint connects to, lowercased: the session host of a
     * {@code jmap://} marker, null for the {@code google://} and
     * {@code msgraph://} ones (the bridge's own fixed hosts) and for a URL
     * naming none.
     */
    static String host(String url) {
        String scheme = scheme(url);
        if (scheme == null || "google".equals(scheme) || "msgraph".equals(scheme)) {
            return null;
        }
        try {
            String host = URI.create(url).getHost();
            if (host == null || host.isEmpty()) {
                return null;
            }
            host = host.toLowerCase(Locale.ROOT);
            return host.startsWith("[") && host.endsWith("]")
                    ? host.substring(1, host.length() - 1)
                    : host;
        } catch (IllegalArgumentException invalid) {
            return null;
        }
    }

    /**
     * An endpoint as the user is shown it before signing in: its scheme, host
     * and any port, without userinfo or path; null for the {@code google://}
     * and {@code msgraph://} markers, which no password signs in to.
     */
    public static String destination(String url) {
        String host = host(url);
        if (host == null) {
            return null;
        }
        String scheme = scheme(url);
        int port = -1;
        try {
            port = URI.create(url).getPort();
        } catch (IllegalArgumentException invalid) {
            // NOTE: host() parsed it, so this does not happen.
        }
        String shown = host.contains(":") ? "[" + host + "]" : host;
        return ("jmap".equals(scheme) ? "https" : scheme)
                + "://"
                + shown
                + (port == -1 ? "" : ":" + port);
    }

    /**
     * Whether an endpoint's host sits outside the address's domain: the user
     * is shown it before a password goes there, a host the address does not
     * name being the one a forged configuration would point at.
     */
    public static boolean otherDomain(String url, String email) {
        String host = host(url);
        int at = email == null ? -1 : email.lastIndexOf('@');
        if (host == null || at < 0) {
            return false;
        }
        String domain = email.substring(at + 1).trim().toLowerCase(Locale.ROOT);
        return !(host.equals(domain) || host.endsWith("." + domain));
    }

    /**
     * Whether every endpoint is one of the provider's own: its marker scheme,
     * or a TLS scheme on one of its hosts. The app's own OAuth client signs
     * in only to those, so a configuration pairing the provider's sign-in
     * with another server's host gets a generic grant instead.
     */
    static boolean allOn(Collection<String> urls, String marker, Set<String> hosts) {
        if (urls.isEmpty()) {
            return false;
        }
        for (String url : urls) {
            if (url == null) {
                continue;
            }
            String scheme = scheme(url);
            if (marker.equals(scheme)) {
                continue;
            }
            if (scheme == null || !TLS.contains(scheme) || !hosts.contains(host(url))) {
                return false;
            }
        }
        return true;
    }
}
