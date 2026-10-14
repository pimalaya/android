package org.pimalaya.account;

import android.util.Log;
import java.net.URI;
import java.util.Collection;
import java.util.Locale;
import java.util.Set;
import org.pimalaya.client.AccountClient;
import org.pimalaya.client.ServiceConfig;

/**
 * Where the connection flow lets a credential go, and how it names it.
 *
 * <p>Only over TLS: https, imaps, smtps, or smtp always upgraded by STARTTLS;
 * http and imap are refused. {@code google://}, {@code msgraph://},
 * {@code jmap://} mark https endpoints the bridge builds.
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
     * What was typed, as the URL the domain's client connects to.
     *
     * <p>Implicit-TLS IMAP on its default port for mail, https for a DAV
     * collection; null for a scheme the domain cannot sign in over safely
     * ({@code imap://}, {@code http://}, any other).
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

    /** A typed submission server as its endpoint; null unless smtps or smtp. */
    public static String submitUrl(String entered) {
        String url = AccountClient.submitUrl(entered.trim());
        String scheme = scheme(url);
        boolean smtp = "smtps".equals(scheme) || "smtp".equals(scheme);
        return smtp && host(url) != null ? url : null;
    }

    /**
     * The endpoint one discovered SMTP config submits through, or null.
     *
     * <p>{@code smtps://} for implicit TLS, {@code smtp://} for STARTTLS; null
     * for a non-SMTP config or a security this client does not drive.
     */
    public static String submissionUrl(ServiceConfig config) {
        if (!"smtp".equals(config.service()) || config.host() == null) {
            return null;
        }
        boolean tls = "tls".equalsIgnoreCase(config.security());
        if (!tls && !"starttls".equalsIgnoreCase(config.security())) {
            Log.w(
                    "pimalaya",
                    "skip smtp at " + config.host() + ": unsupported security " + config.security());
            return null;
        }
        return (tls ? "smtps://" : "smtp://") + config.host() + ":" + config.port();
    }

    /**
     * A token-signed mail endpoint naming {@code email} as its user.
     *
     * <p>SASL XOAUTH2 names the user beside the token, and the URL carries it
     * (RFC 5092 section 3.2) while the credential stays the bare token. Any
     * other endpoint is returned as it is.
     */
    public static String asUser(String url, String email) {
        return url.startsWith("imap") || url.startsWith("smtp")
                ? AccountClient.withUser(url, email)
                : url;
    }

    /**
     * The URL an account for {@code email} connects to for one config.
     *
     * <p>Null when this app cannot drive it: HTTP must be https; TCP gets
     * implicit TLS only, the IMAP client having no STARTTLS step (it would
     * connect in the clear).
     */
    public static String endpointUrl(ServiceConfig config, String email) {
        if ("msgraph".equals(config.service()) || "msgraphCalendar".equals(config.service())) {
            return AccountClient.msgraphBase(email);
        }
        if ("gmail".equals(config.service()) || "gcal".equals(config.service())) {
            return AccountClient.googleBase(email);
        }
        if (config.url() != null) {
            // NOTE: an HTTP endpoint is https or nothing: a plain one, from a
            // PACC document or an autoconfig, would carry the password in
            // clear.
            if (!"https".equals(Endpoints.scheme(config.url()))) {
                Log.w("pimalaya", "skip " + config.service() + ": not https");
                return null;
            }
            return "jmap".equals(config.service())
                    ? AccountClient.jmapBase(config.url())
                    : config.url();
        }
        if (config.host() == null || !"imap".equals(config.service())) {
            return null;
        }
        if (!"tls".equalsIgnoreCase(config.security())) {
            Log.w(
                    "pimalaya",
                    "skip " + config.service() + " at " + config.host()
                            + ": unsupported security " + config.security());
            return null;
        }
        return "imaps://" + config.host() + ":" + config.port();
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
     * The lowercased host an endpoint connects to.
     *
     * <p>The session host of a {@code jmap://} marker; null for
     * {@code google://}, {@code msgraph://} (the bridge's fixed hosts) and a
     * URL naming none.
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
     * An endpoint as shown before signing in: scheme, host and any port.
     *
     * <p>No userinfo or path; null for the {@code google://} and
     * {@code msgraph://} markers, which no password signs in to.
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
     * Whether an endpoint's host sits outside the address's domain.
     *
     * <p>The user is shown it before a password goes there: such a host is what
     * a forged configuration would point at.
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
     * Whether every endpoint is one of the provider's own.
     *
     * <p>Its marker scheme, or a TLS scheme on one of its hosts. The app's own
     * OAuth client signs in only to those; anything else gets a generic grant.
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
