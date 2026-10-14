package org.pimalaya.setup;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.pimalaya.account.Endpoints;
import org.pimalaya.account.Oauth;
import org.pimalaya.account.PimDomain;
import org.pimalaya.client.AccountClient;
import org.pimalaya.client.AuthMethod;
import org.pimalaya.client.ServiceConfig;

/**
 * The connection wizard's decisions over discovered options: which one the
 * standard setup picks, which sending rows go with a reading choice, and
 * which picks share one sign-in. No views and no network.
 */
final class SetupPlan {
    private SetupPlan() {}

    /**
     * The discovered half of one domain's options: one per configuration
     * serving it and authentication method, the configurations ranked for
     * the domain and the methods by {@link #authRank}. A configuration this
     * app cannot drive is left out. {@code emailLogin} is the login where a
     * configuration names none, {@code protocolName} the user-facing name of
     * a service kind.
     */
    static List<SetupOption> discoveredOptions(
            PimDomain domain,
            List<ServiceConfig> searched,
            String email,
            String emailLogin,
            Function<String, String> protocolName) {
        List<SetupOption> options = new ArrayList<>();

        List<ServiceConfig> configs = new ArrayList<>(PimDomain.configsFor(domain, searched));
        java.util.Collections.sort(
                configs,
                (left, right) ->
                        Integer.compare(domain.rank(left.service()), domain.rank(right.service())));

        for (ServiceConfig config : configs) {
            String baseUrl = Endpoints.endpointUrl(config, email);
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
     * <p>Google's and Microsoft's own APIs first, which sign in through the
     * app's registration at either; elsewhere the configuration ranked best
     * for the domain ({@link PimDomain#rank}), then OAuth over an API token
     * over a password. The user picks the domains and the setup picks the rest.
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

    /** The standard setup's order: the service for the domain first, then the sign-in. */
    static int standardRank(PimDomain domain, SetupOption option, String email) {
        int service = proprietary(option, email) ? 0 : 1 + domain.rank(option.service);
        return service * 10 + authRank(option.method.type());
    }

    /**
     * Whether an option reads Google's or Microsoft's own API: a discovered
     * Gmail, Google Calendar or Graph service, or a provider sign-in to the
     * People API or Graph.
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
     * Auth precedence: OAuth 2.0 over API token over password, the device grant
     * last among the OAuth ones since a phone has a browser.
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
     * <p>Reading over a provider API sends through that API, and SMTP goes
     * with anything else: Microsoft's SMTP signs in with Outlook's token
     * and Graph with Graph's, so pairing one with the other would be a
     * second credential to store and renew for the same mailbox. Gmail's
     * one token would cover both, and is kept to the same shape.
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
     * The sign-ins a selection needs, in order, one group of domains each:
     * the picked options in the map's order (domain order for an
     * {@link java.util.EnumMap}), pooled by {@link #sameSignIn} against the
     * first option of each group.
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

    /** Whether two picked options sign in once for both. */
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
                && java.util.Objects.equals(left.detail, right.detail)
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
     * The RFC 8707 resource an OAuth grant asks for, or null when there is
     * none to ask for.
     *
     * <p>An HTTP service names itself: the URL it was discovered at is the URI
     * the server protects. A text one does not, and IMAP is the case that
     * matters, because nothing in PACC, autoconfig or SRV carries a resource
     * for it and a server that requires one (Fastmail answers
     * {@code invalid_target} without) leaves no other way in.
     *
     * <p>So a text service falls back to the origin of its authorization
     * endpoint, on the reasoning that a provider issuing tokens at
     * {@code https://api.example.com/oauth/authorize} protects its resources
     * under the same origin. That is a derivation, not a discovery: a provider
     * that separates the two will need its resource from RFC 9728 protected
     * resource metadata, which nothing fetches yet.
     *
     * <p>A provider rule's config asks for none: Google and Microsoft
     * authorize by scope, and answer a resource with {@code invalid_target}.
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

    static String hostOf(String url) {
        try {
            return new java.net.URL(url).getHost();
        } catch (Exception error) {
            return url;
        }
    }
}
