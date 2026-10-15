package org.pimalaya.client;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * The account verbs of the bridge (rs/ffi/account.rs).
 *
 * <p>Config search, OAuth 2.0 refresh, metadata and registration (the code
 * exchange is {@link OauthSession}'s), account base URLs. Calls taking a
 * transport block: run them off the main thread.
 */
public class AccountClient {
    /**
     * Runs the MX provider probe: the provider configs, empty when none match.
     *
     * <p>One DNS lookup matching the MX against the provider rules (Google,
     * Microsoft, custom domains included), beside {@link #searchAll}. A null
     * {@code resolver} is the default DNS-over-HTTPS one.
     */
    public List<ServiceConfig> searchProvider(Transport transport, String email, String resolver) {
        return configs(Native.searchProvider(transport, email, resolver));
    }

    /**
     * Searches every discovery mechanism for an address's or domain's configs.
     *
     * <p>Provider rules first, then PACC, RFC 6764, autoconfig, RFC 8620, run
     * in parallel, failures skipped; endpoints but a rule's are probed for auth
     * schemes. A null {@code resolver} is the default DNS-over-HTTPS one.
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

    /** Parses one mechanism's output into configs; a failed one is empty. */
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
     * Refreshes OAuth 2.0 tokens over the grant.
     *
     * <p>A null grant scope keeps the server default; its client secret rides
     * along when one was issued.
     */
    public OauthTokens refresh(Transport transport, OauthGrant grant, String refreshToken) {
        JSONObject reply =
                Replies.object(
                        Native.oauthRefreshAccessToken(
                                transport,
                                grant.tokenEndpoint(),
                                grant.clientId(),
                                grant.clientSecret() == null ? "" : grant.clientSecret(),
                                refreshToken,
                                grant.scope() == null ? "" : grant.scope()));
        return OauthTokens.from(reply);
    }

    /** Fetches an authorization server's RFC 8414 metadata from its issuer. */
    public ServerMetadata serverMetadata(Transport transport, String issuer) {
        return ServerMetadata.from(Replies.object(Native.oauthServerMetadata(transport, issuer)));
    }

    /**
     * Registers a public client (RFC 7591), returning its issued client id.
     *
     * <p>No secret, the loopback redirect URI, code and refresh grants.
     */
    public String registerClient(Transport transport, ClientRegistration registration) {
        JSONObject reply =
                Replies.object(
                        Native.oauthRegisterClient(
                                transport,
                                registration.endpoint(),
                                registration.redirectUri(),
                                registration.clientName() == null ? "" : registration.clientName(),
                                registration.scope() == null ? "" : registration.scope()));
        return Json.string(reply, "client_id");
    }

    /** Google's CardDAV principal root for the email, PROPFIND's start. */
    public static String googleCarddavBase(String email) {
        return base("googleCarddav", email);
    }

    /** A Google API account's base URL: People, Gmail or Calendar. */
    public static String googleBase(String email) {
        return base("google", email);
    }

    /** A Microsoft Graph account's base URL for the email. */
    public static String msgraphBase(String email) {
        return base("msgraph", email);
    }

    /**
     * A JMAP account's base URL wrapping the HTTPS session URL.
     *
     * <p>A bare host triggers {@code /.well-known/jmap} discovery.
     */
    public static String jmapBase(String sessionUrl) {
        return base("jmap", sessionUrl);
    }

    /**
     * What was typed for a submission server, as the endpoint it opens.
     *
     * <p>STARTTLS on 587 (RFC 6409) and 25, implicit TLS on any other port,
     * 465 (RFC 8314 3.3) when none was typed. A URL passes through.
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
     * The endpoint with a user in its userinfo (RFC 5092 3.2).
     *
     * <p>What SASL XOAUTH2 presents beside a token, which carries no login.
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
