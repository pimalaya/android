package org.pimalaya.setup;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import org.pimalaya.account.Endpoints;
import org.pimalaya.account.Oauth;
import org.pimalaya.account.PimDomain;
import org.pimalaya.client.AccountClient;
import org.pimalaya.client.AuthMethod;
import org.pimalaya.client.ServiceConfig;

/**
 * The connection wizard's decisions over discovered options.
 *
 * <p>Which one the standard setup picks, which sending rows go with a reading
 * choice, and which picks share one sign-in. No views and no network.
 */
final class SetupPlan {
    private SetupPlan() {}

    /**
     * What the search found for an entered address.
     *
     * @param searched the configurations found
     * @param email the address entered
     * @param emailLogin the login where a configuration names none
     */
    record Discovery(List<ServiceConfig> searched, String email, String emailLogin) {}

    /**
     * One domain's discovered options, by configuration and auth method.
     *
     * <p>Configurations ranked for the domain, methods by {@link #authRank};
     * one this app cannot drive or does not offer ({@link #offered}) is left
     * out. {@code protocolName} names a service kind for the user.
     */
    static List<SetupOption> discoveredOptions(
            PimDomain domain, Discovery discovery, Function<String, String> protocolName) {
        List<ServiceConfig> searched = discovery.searched();
        String email = discovery.email();
        String emailLogin = discovery.emailLogin();

        List<SetupOption> options = new ArrayList<>();

        List<ServiceConfig> configs = new ArrayList<>(PimDomain.configsFor(domain, searched));
        java.util.Collections.sort(
                configs,
                (left, right) ->
                        Integer.compare(domain.rank(left.service()), domain.rank(right.service())));

        for (ServiceConfig config : configs) {
            String baseUrl = offered(config) ? Endpoints.endpointUrl(config, email) : null;
            if (baseUrl == null) {
                continue;
            }
            String detail = config.url() != null ? hostOf(config.url()) : config.host();
            String login = config.username() != null ? config.username() : emailLogin;

            List<AuthMethod> methods = new ArrayList<>(config.auth());
            java.util.Collections.sort(
                    methods,
                    (left, right) -> Integer.compare(authRank(left.type()), authRank(right.type())));
            for (AuthMethod method : methods) {
                options.add(
                        new SetupOption(
                                config.service(),
                                protocolName.apply(config.service()),
                                detail,
                                baseUrl,
                                resourceOf(config, method),
                                method,
                                login));
            }
        }
        return options;
    }

    /**
     * The standard setup's way into one domain, or null when it offers none.
     *
     * <p>Google's and Microsoft's own APIs first (the app's registration); else
     * the configuration ranked best ({@link PimDomain#rank}), then OAuth over
     * API token over password.
     */
    static SetupOption standardOption(PimDomain domain, List<SetupOption> options, String email) {
        SetupOption best = null;
        for (SetupOption option : options) {
            if (option.method == null) {
                continue;
            }
            if (best == null
                    || standardRank(domain, option, email) < standardRank(domain, best, email)) {
                best = option;
            }
        }
        return best;
    }

    /** The standard setup's order: the domain's service first, then sign-in. */
    static int standardRank(PimDomain domain, SetupOption option, String email) {
        int service = proprietary(option, email) ? 0 : 1 + domain.rank(option.service);
        return service * 10 + authRank(option.method.type());
    }

    /**
     * Whether an option reads Google's or Microsoft's own API.
     *
     * <p>A discovered Gmail, Google Calendar or Graph service, or a provider
     * sign-in to the People API or Graph.
     */
    static boolean proprietary(SetupOption option, String email) {
        if (option.service == null) {
            return option.baseUrl != null
                    && (option.baseUrl.equals(AccountClient.googleBase(email))
                            || option.baseUrl.equals(AccountClient.msgraphBase(email)));
        }
        switch (option.service) {
            case "gmail":
            case "gcal":
            case "msgraph":
            case "msgraphCalendar":
                return true;
            default:
                return false;
        }
    }

    /**
     * Auth precedence: OAuth 2.0 over API token over password.
     *
     * <p>The device grant last among the OAuth ones, a phone having a browser.
     */
    static int authRank(AuthMethod.Type type) {
        switch (type) {
            case PASSWORD:
                return 3;
            case BEARER:
                return 2;
            case OAUTH_DEVICE_AUTHORIZATION_GRANT:
                return 1;
            default:
                return 0;
        }
    }

    /**
     * Whether a sending row goes with what mail is read through.
     *
     * <p>A provider API sends through itself, SMTP goes with the rest:
     * Microsoft's SMTP takes Outlook's token, Graph Graph's, two credentials
     * for one mailbox. Gmail's token covers both, kept to the same shape.
     */
    static boolean offers(SetupOption selected, SubmitOption option) {
        if (option.none()) {
            return true;
        }
        String api =
                selected != null && selected.readsOverApi()
                        ? selected.service
                        : null;
        if (!java.util.Objects.equals(option.through, api)) {
            return false;
        }
        if (option.auth == null || selected == null) {
            return true;
        }
        // NOTE: a manual mail server is signed in to with a password.
        AuthMethod.Type reading =
                selected.method == null
                        ? AuthMethod.Type.PASSWORD
                        : selected.method.type();
        return authKind(option.auth) == authKind(reading);
    }

    /** An authentication method's kind: OAuth's grants are one kind. */
    private static int authKind(AuthMethod.Type type) {
        switch (type) {
            case PASSWORD:
                return 2;
            case BEARER:
                return 1;
            default:
                return 0;
        }
    }

    /** The first sending row that goes with what mail is read through. */
    static SubmitOption firstOffered(SetupOption selected, List<SubmitOption> options) {
        for (SubmitOption option : options) {
            if (!option.none() && !option.manual && offers(selected, option)) {
                return option;
            }
        }
        return null;
    }

    /**
     * The sign-ins a selection needs, in order, one group of domains each.
     *
     * <p>Picked options in the map's order (domain order for an {@link
     * java.util.EnumMap}), pooled by {@link #sameSignIn} against each group's
     * first.
     */
    static List<List<PimDomain>> signIns(Map<PimDomain, SetupOption> picked) {
        List<List<PimDomain>> groups = new ArrayList<>();
        List<SetupOption> leads = new ArrayList<>();
        for (Map.Entry<PimDomain, SetupOption> entry : picked.entrySet()) {
            int shared = -1;
            for (int index = 0; index < leads.size(); index++) {
                if (sameSignIn(leads.get(index), entry.getValue())) {
                    shared = index;
                    break;
                }
            }

            if (shared >= 0) {
                groups.get(shared).add(entry.getKey());
            } else {
                List<PimDomain> group = new ArrayList<>();
                group.add(entry.getKey());
                groups.add(group);
                leads.add(entry.getValue());
            }
        }
        return groups;
    }

    /**
     * Whether two picked options sign in once for both.
     *
     * <p>A grant, behind one authorization server for one audience; a password
     * or a token, at one server: a JMAP session serves every domain it does at
     * one URL, while IMAP, SMTP, CardDAV and CalDAV are a server each, even on
     * one host, any of which may take another login.
     */
    static boolean sameSignIn(SetupOption left, SetupOption right) {
        if (left.isOauth() || right.isOauth()) {
            return left.isOauth()
                    && right.isOauth()
                    && sameAuthorizationServer(left.method, right.method)
                    && audienceOf(left).equals(audienceOf(right));
        }
        if (left.isManual() || right.isManual()) {
            return false;
        }
        return left.method.type() == right.method.type()
                && left.baseUrl != null
                && left.baseUrl.equals(right.baseUrl)
                && java.util.Objects.equals(left.login, right.login);
    }

    /** Whether two OAuth methods would send the user to the same server. */
    static boolean sameAuthorizationServer(AuthMethod left, AuthMethod right) {
        if (left.type() != right.type()) {
            return false;
        }
        if (left.issuer() != null || right.issuer() != null) {
            return left.issuer() != null && left.issuer().equals(right.issuer());
        }
        return left.authorizationEndpoint() != null
                && left.authorizationEndpoint().equals(right.authorizationEndpoint());
    }

    /** What one option's grant is for ({@link Oauth#audience}). */
    static String audienceOf(SetupOption option) {
        return option.method == null
                ? Oauth.audience(null, option.resource, null)
                : Oauth.audience(
                        option.method.authorizationEndpoint(), option.resource, option.method.scope());
    }

    static void addScopes(java.util.Set<String> scopes, String scope) {
        if (scope == null || scope.isEmpty()) {
            return;
        }
        for (String part : scope.split("\\s+")) {
            if (!part.isEmpty()) {
                scopes.add(part);
            }
        }
    }

    /**
     * The RFC 8707 resource an OAuth grant asks for, null for provider rules.
     *
     * <p>An HTTP service's discovery URL. A text one (IMAP: Fastmail answers
     * {@code invalid_target} without) takes its authorization endpoint's
     * origin, pending RFC 9728. Google and Microsoft reject one.
     */
    static String resourceOf(ServiceConfig config, AuthMethod method) {
        if (fromProvider(config)) {
            return null;
        }
        if (config.url() != null) {
            return config.url();
        }
        if (method == null || method.authorizationEndpoint() == null) {
            return null;
        }
        try {
            java.net.URL endpoint = new java.net.URL(method.authorizationEndpoint());
            String port = endpoint.getPort() == -1 ? "" : ":" + endpoint.getPort();
            return endpoint.getProtocol() + "://" + endpoint.getHost() + port + "/";
        } catch (Exception error) {
            return null;
        }
    }

    /** Whether a config came from a fixed provider rule (Google, Microsoft). */
    static boolean fromProvider(ServiceConfig config) {
        return config.source() != null && config.source().startsWith("provider:");
    }

    /**
     * Whether the app offers a discovered config: none of Google's IMAP, SMTP,
     * CardDAV or CalDAV, its mail, contacts and calendars connecting over its
     * APIs alone. Gmail's IMAP and SMTP are told by their hosts too, since the
     * autoconfig databases list them.
     */
    static boolean offered(ServiceConfig config) {
        String service = config.service();
        boolean dav = "carddav".equals(service) || "caldav".equals(service);
        boolean mail = "imap".equals(service) || "smtp".equals(service);
        if (!dav && !mail) {
            return true;
        }
        if ("provider:google".equals(config.source())) {
            return false;
        }
        String host = config.host();
        if (!mail || host == null) {
            return true;
        }
        return !GMAIL_HOSTS.contains(host.toLowerCase(java.util.Locale.ROOT));
    }

    /** Gmail's IMAP and SMTP hosts, the app reaching Gmail over its API. */
    private static final Set<String> GMAIL_HOSTS =
            Set.of("imap.gmail.com", "smtp.gmail.com", "imap.googlemail.com", "smtp.googlemail.com");

    /** The first labels of a host naming a service rather than whose it is. */
    private static final Set<String> SERVICE_LABELS =
            Set.of("api", "caldav", "carddav", "dav", "imap", "jmap", "mail", "smtp", "webdav");

    /**
     * The server an option connects to, the way a reader names it.
     *
     * <p>Its host with a leading service label dropped, so imap.fastmail.com
     * and caldav.fastmail.com both read fastmail.com; a host of two labels is
     * kept whole. Any scheme, the {@code jmap://} marker included.
     */
    static String serverName(SetupOption option) {
        return serverName(option.baseUrl);
    }

    /** The server at {@code url}, named as {@link #serverName(SetupOption)}. */
    static String serverName(String url) {
        String base = url == null ? "" : url;
        String host;
        try {
            host = java.net.URI.create(base).getHost();
        } catch (IllegalArgumentException error) {
            host = null;
        }
        if (host == null) {
            return base;
        }
        String[] labels = host.split("\\.");
        if (labels.length > 2
                && SERVICE_LABELS.contains(labels[0].toLowerCase(java.util.Locale.ROOT))) {
            return host.substring(labels[0].length() + 1);
        }
        return host;
    }

    /**
     * Whose own account a provider-API option signs in with: Google,
     * Microsoft, or null for any other option ({@link #proprietary}).
     */
    static String provider(SetupOption option, String email) {
        if (!proprietary(option, email)) {
            return null;
        }
        boolean google =
                "gmail".equals(option.service)
                        || "gcal".equals(option.service)
                        || (option.service == null
                                && AccountClient.googleBase(email).equals(option.baseUrl));
        return google ? "Google" : "Microsoft";
    }

    /**
     * The host an endpoint names, or the endpoint itself when it names none.
     *
     * <p>Read from the URI: java.net.URL knows no imaps or smtps, and would
     * answer the whole endpoint for a mail server. A provider API's marker
     * names the account, not a host.
     */
    static String hostOf(String url) {
        String scheme = org.pimalaya.account.Endpoints.scheme(url);
        if ("google".equals(scheme) || "msgraph".equals(scheme)) {
            return url;
        }
        try {
            String host = java.net.URI.create(url).getHost();
            return host == null ? url : host;
        } catch (Exception error) {
            return url;
        }
    }
}
