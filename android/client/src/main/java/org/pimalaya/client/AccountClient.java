package org.pimalaya.client;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * The account verbs of the bridge (rs/ffi/account.rs): config search, OAuth
 * 2.0 token refresh, server metadata and client registration (the code
 * exchange lives on {@link OauthSession}), and what an account URL tells
 * about its backend. Every call that takes a transport blocks, so callers
 * must run it off the main thread.
 */
public class AccountClient {
    /**
     * Runs the MX provider probe: one DNS lookup matching the mail
     * exchanges against the fixed provider rules, catching every
     * domain whose mail lives at Google or Microsoft (gmail.com and
     * outlook.com included, their own exchanges match too). Meant to
     * run in parallel with {@link #searchAll} so the caller decides
     * what to take. Returns the raw provider configs (their source
     * naming the provider), empty when no rule matched. Resolver as
     * in {@link PimalayaClient#discover}.
     */
    public List<ServiceConfig> searchProvider(Transport transport, String email, String resolver) {
        return configs(Native.searchProvider(transport, email, resolver));
    }

    /**
     * Searches every discovery mechanism for service configs of every
     * domain the app covers: the fixed provider rules, PACC, RFC 6764
     * CardDAV and CalDAV resolve, Mozilla autoconfig (IMAP and SMTP)
     * and RFC 8620 JMAP resolve, each carrying its endpoint and
     * authentication methods, so the connection screen can list what
     * the address actually offers. The input is an email address or a
     * bare domain (every mechanism is domain-driven; a domain just
     * skips the username hints). The mechanisms run in parallel, each
     * on its own transport; a failing mechanism is skipped. Their
     * outputs merge in mechanism-priority order, and the merged
     * configs' endpoints are probed in parallel for the authentication
     * schemes they actually advertise, a provider rule's excepted: what
     * it says is the provider's own word. Resolver as in
     * {@link PimalayaClient#discover}.
     *
     * <p>The provider rules rank first, as io-pim-discovery's own sweep
     * ranks them: they name Google's and Microsoft's endpoints and sign-ins
     * for a custom domain as much as for their own, which nothing the
     * domain publishes does.
     */
    public List<ServiceConfig> searchAll(String email, String resolver) {
        String[] outputs = new String[6];
        Thread[] mechanisms = new Thread[outputs.length];
        for (int index = 0; index < mechanisms.length; index++) {
            final int mechanism = index;
            mechanisms[index] =
                    new Thread(
                            () -> {
                                Transport transport = new Transport();
                                try {
                                    outputs[mechanism] =
                                            searchMechanism(mechanism, transport, email, resolver);
                                } finally {
                                    transport.close();
                                }
                            });
            mechanisms[index].start();
        }
        joinAll(mechanisms);

        JSONArray lists = new JSONArray();
        for (String output : outputs) {
            lists.put(configLists(output));
        }
        JSONArray merged = Replies.array(Native.searchMerge(lists.toString()));

        String[] probed = new String[merged.length()];
        Thread[] probes = new Thread[probed.length];
        for (int index = 0; index < probes.length; index++) {
            final int at = index;
            final JSONObject found = Json.object(merged, at);
            final String config = found.toString();
            if ("provider".equals(found.optString("source"))) {
                probed[at] = config;
                continue;
            }
            probes[index] =
                    new Thread(
                            () -> {
                                Transport transport = new Transport();
                                try {
                                    probed[at] = Native.searchProbe(transport, config);
                                } finally {
                                    transport.close();
                                }
                                try {
                                    Replies.object(probed[at]);
                                } catch (RuntimeException error) {
                                    // NOTE: a failed probe keeps the
                                    // config as discovered.
                                    probed[at] = config;
                                }
                            });
            probes[index].start();
        }
        joinAll(probes);

        JSONArray result = new JSONArray();
        for (String config : probed) {
            result.put(Replies.object(config));
        }
        return configs(result.toString());
    }

    /** One discovery mechanism run, by priority rank. */
    private static String searchMechanism(
            int mechanism, Transport transport, String email, String resolver) {
        switch (mechanism) {
            case 0:
                return Native.searchProvider(transport, email, resolver);
            case 1:
                return Native.searchPacc(transport, email, resolver);
            case 2:
                return Native.searchCarddav(transport, email, resolver);
            case 3:
                return Native.searchCaldav(transport, email, resolver);
            case 4:
                return Native.searchAutoconfig(transport, email, resolver);
            default:
                return Native.searchJmap(transport, email, resolver);
        }
    }

    /**
     * Parses one mechanism's output into a config array; a failed
     * mechanism (error reply, or none at all) is skipped as an empty
     * list, like the serial search chain did.
     */
    private static JSONArray configLists(String output) {
        if (output == null) {
            return new JSONArray();
        }
        try {
            return Replies.array(output);
        } catch (RuntimeException error) {
            return new JSONArray();
        }
    }

    /** Joins every thread started, restoring the interrupt flag if raised. */
    private static void joinAll(Thread[] threads) {
        for (Thread thread : threads) {
            if (thread == null) {
                continue;
            }
            try {
                thread.join();
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /**
     * Refreshes OAuth 2.0 tokens; {@code scope} is the space-separated
     * scope list of the original grant (null keeps the server default)
     * and {@code clientSecret} rides along when the registration
     * issued one (null for none). The code-exchange side lives on
     * {@link OauthSession}.
     */
    public OauthTokens oauthRefresh(Transport transport, String tokenEndpoint,
            String clientId,
            String clientSecret,
            String refreshToken,
            String scope) {
        JSONObject reply =
                Replies.object(
                        Native.oauthRefreshAccessToken(
                                transport,
                                tokenEndpoint,
                                clientId,
                                clientSecret == null ? "" : clientSecret,
                                refreshToken,
                                scope == null ? "" : scope));
        return OauthTokens.from(reply);
    }

    /**
     * Fetches an authorization server's RFC 8414 metadata from its
     * issuer, so onboarding can drive the code grant and tell whether
     * the server lets a public client register itself (RFC 7591).
     */
    public ServerMetadata oauthServerMetadata(Transport transport, String issuer) {
        return new ServerMetadata(Replies.object(Native.oauthServerMetadata(transport, issuer)));
    }

    /**
     * Registers a public client at the given RFC 7591 registration
     * endpoint (no secret, the loopback redirect URI, code + refresh
     * grants), returning the server-issued client id. The client
     * secret rides in {@link OauthTokens}-style JSON but a public
     * client gets none.
     */
    public String oauthRegisterClient(Transport transport, String registrationEndpoint, String redirectUri, String clientName, String scope) {
        JSONObject reply =
                Replies.object(
                        Native.oauthRegisterClient(
                                transport,
                                registrationEndpoint,
                                redirectUri,
                                clientName == null ? "" : clientName,
                                scope == null ? "" : scope));
        return Json.string(reply, "client_id");
    }

    /**
     * Google's CardDAV principal root for the email; the standard
     * PROPFIND discovery runs from there.
     */
    public static String googleCarddavBase(String email) {
        return base("googleCarddav", email);
    }

    /** A Google API account's base URL for the email: People, Gmail or Calendar. */
    public static String googleBase(String email) {
        return base("google", email);
    }

    /** A Microsoft Graph account's base URL for the email. */
    public static String msgraphBase(String email) {
        return base("msgraph", email);
    }

    /**
     * A JMAP account's base URL wrapping the HTTPS session URL (a bare
     * host triggers {@code /.well-known/jmap} discovery).
     */
    public static String jmapBase(String sessionUrl) {
        return base("jmap", sessionUrl);
    }

    /**
     * What was typed for a submission server, as the endpoint it opens:
     * STARTTLS on the submission port (587, RFC 6409) and on 25, implicit
     * TLS on any other, 465 (RFC 8314 section 3.3) when none was typed. A
     * URL passes through.
     */
    public static String submitUrl(String entered) {
        if (entered.contains("://")) {
            return entered;
        }
        if (!entered.contains(":")) {
            return "smtps://" + entered + ":465";
        }
        String port = entered.substring(entered.lastIndexOf(':') + 1);
        return (port.equals("587") || port.equals("25") ? "smtp://" : "smtps://") + entered;
    }

    /**
     * The endpoint with a user in its userinfo, the way RFC 5092 section
     * 3.2 names who an IMAP URL signs in as: what SASL XOAUTH2 presents
     * beside a token, which carries no login of its own.
     */
    public static String withUser(String url, String user) {
        try {
            URI uri = URI.create(url);
            return new URI(
                            uri.getScheme(),
                            user,
                            uri.getHost(),
                            uri.getPort(),
                            uri.getPath(),
                            uri.getQuery(),
                            uri.getFragment())
                    .toASCIIString();
        } catch (URISyntaxException error) {
            throw new PimalayaException("Invalid endpoint '" + url + "': " + error.getMessage());
        }
    }

    /** Builds one account base URL through the bridge. */
    private static String base(String kind, String value) {
        return Json.string(Replies.object(Native.accountBase(kind, value)), "url");
    }

    /**
     * True when the account's cards are account-level resources with
     * m:n addressbook memberships (JMAP, Google): they list once per
     * account through {@link PimalayaClient#listAccountCards}, not per addressbook.
     * CardDAV and Graph cards live in the one collection they were
     * listed from and go through {@link ContactClient#listCards}.
     */
    public static boolean isAccountLevel(Account account) {
        return account != null && isAccountLevel(account.baseUrl);
    }

    /**
     * True when the URL (an account base URL, or a collection URL
     * derived from one) belongs to an account-level backend.
     */
    public static boolean isAccountLevel(String url) {
        return info(url).optBoolean("accountLevel");
    }

    /**
     * True when a write to the calendar behind the URL carries an override
     * of one occurrence to the server: CalDAV's in the object, Graph's and
     * Google's through the occurrence's instance, JMAP's inside the event's
     * {@code recurrenceOverrides}.
     */
    public static boolean writesOverrides(String url) {
        return info(url).optBoolean("writesOverrides");
    }

    /** True when a write to the calendar behind the URL carries an {@code EXDATE}. */
    public static boolean writesExdates(String url) {
        return info(url).optBoolean("writesExdates");
    }

    /**
     * True when the calendar behind the URL takes event writes at all: every backend's does, the
     * local account's none.
     */
    public static boolean writesEvents(String url) {
        return info(url).optBoolean("writesEvents");
    }

    /**
     * True when the account's mail submits through the session it reads from (JMAP, Graph,
     * Gmail), needing no submit endpoint of its own.
     */
    public static boolean submitsOverSession(Account account) {
        return account != null && info(account.baseUrl).optBoolean("submitsOverSession");
    }

    /** True when the URL belongs to a plain CardDAV backend. */
    public static boolean isCarddav(String url) {
        return "carddav".equals(info(url).optString("backend"));
    }

    /** True when the account's backend is Microsoft Graph. */
    public static boolean isGraph(Account account) {
        return account != null && "graph".equals(info(account.baseUrl).optString("backend"));
    }

    /** True when the account's backend is the Google People API. */
    public static boolean isGoogle(Account account) {
        return account != null && "google".equals(info(account.baseUrl).optString("backend"));
    }

    /** True when the account's backend speaks JMAP (RFC 8620). */
    public static boolean isJmap(Account account) {
        return account != null && "jmap".equals(info(account.baseUrl).optString("backend"));
    }

    /** Parsed backend info by URL, cached (pure computation). */
    private static final Map<String, JSONObject> infos = new ConcurrentHashMap<>();

    /** The URL's {@code {backend, accountLevel}} info. */
    private static JSONObject info(String url) {
        return infos.computeIfAbsent(
                url == null ? "" : url, key -> Replies.object(Native.accountInfo(key)));
    }

    /** Parses a search reply into service configs. */
    private static List<ServiceConfig> configs(String json) {
        JSONArray reply = Replies.array(json);

        List<ServiceConfig> configs = new ArrayList<>(reply.length());
        for (int index = 0; index < reply.length(); index++) {
            configs.add(ServiceConfig.from(Json.object(reply, index)));
        }
        return configs;
    }
}
