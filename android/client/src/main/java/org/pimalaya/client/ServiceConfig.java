package org.pimalaya.client;

import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * One discovered way to use one service: where to connect, how to
 * authenticate, and which mechanism found it. Produced by
 * {@link AccountClient#searchAll}.
 *
 * <p>Flattened from io-pim-discovery's {@code DiscoveryServiceConfig}: its
 * endpoint into {@link #url} or {@link #host}, {@link #port} and
 * {@link #security}, its provider into {@link #source}.
 *
 * @param service service kind, e.g. {@code carddav}
 * @param url HTTP endpoint URL; null for TCP endpoints
 * @param host TCP host; null for HTTP endpoints
 * @param port TCP port; 0 for HTTP endpoints
 * @param security TCP security ({@code plain}, {@code starttls},
 *     {@code tls}); null for HTTP endpoints
 * @param username login to present, when the mechanism advertises one
 * @param auth authentication methods the service accepts
 * @param source mechanism that produced this config:
 *     {@code provider:google}, {@code provider:microsoft}, {@code pacc},
 *     {@code dav}, ...
 */
public record ServiceConfig(
        String service,
        String url,
        String host,
        int port,
        String security,
        String username,
        List<AuthMethod> auth,
        String source) {
    /** Parses one config from the bridge's JSON shape. */
    static ServiceConfig from(JSONObject json) {
        try {
            String url = null;
            String host = null;
            int port = 0;
            String security = null;

            JSONObject endpoint = json.getJSONObject("endpoint");
            if (endpoint.has("http")) {
                url = endpoint.getString("http");
            } else {
                JSONObject tcp = endpoint.getJSONObject("tcp");
                host = tcp.getString("host");
                port = tcp.getInt("port");
                security = tcp.getString("security");
            }

            List<AuthMethod> auth = new ArrayList<>();
            JSONArray methods = json.getJSONArray("auth");
            for (int index = 0; index < methods.length(); index++) {
                auth.add(AuthMethod.from(methods.get(index)));
            }

            return new ServiceConfig(
                    json.getString("service"),
                    url,
                    host,
                    port,
                    security,
                    json.isNull("username") ? null : json.getString("username"),
                    auth,
                    source(json));
        } catch (JSONException error) {
            throw new PimalayaException("Unreadable service config: " + error.getMessage());
        }
    }

    /**
     * Flattens the source tag: a fixed provider rule comes as source
     * {@code "provider"} beside a {@code provider} field naming it.
     */
    private static String source(JSONObject json) throws JSONException {
        String source = json.getString("source");
        if ("provider".equals(source) && json.has("provider")) {
            return "provider:" + json.getString("provider");
        }
        return source;
    }
}
