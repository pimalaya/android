package org.pimalaya;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.StringJoiner;

/**
 * The few HTTP requests the suite makes itself, minting tokens and preparing
 * servers: JSON in, JSON out. A failure names the method, the URL and what
 * the server answered, never a header, so no credential reaches a report.
 */
final class E2eHttp {
    private E2eHttp() {}

    /** A JSON request with a bearer token; null body for none. */
    static JSONObject json(String method, String url, String token, JSONObject body)
            throws IOException {
        byte[] bytes = body == null ? null : body.toString().getBytes(StandardCharsets.UTF_8);
        return send(method, url, token, "application/json", bytes);
    }

    /** A form-encoded POST, the token endpoints' shape (RFC 6749 section 4). */
    static JSONObject form(String url, Map<String, String> fields) throws IOException {
        StringJoiner joined = new StringJoiner("&");
        for (Map.Entry<String, String> field : fields.entrySet()) {
            joined.add(
                    URLEncoder.encode(field.getKey(), "UTF-8")
                            + "="
                            + URLEncoder.encode(field.getValue(), "UTF-8"));
        }
        return send(
                "POST",
                url,
                null,
                "application/x-www-form-urlencoded",
                joined.toString().getBytes(StandardCharsets.UTF_8));
    }

    /** Any request; an empty object for an answer with no body. */
    static JSONObject send(String method, String url, String token, String type, byte[] body)
            throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setConnectTimeout(15_000);
        connection.setReadTimeout(60_000);
        connection.setRequestMethod(method);
        connection.setRequestProperty("Accept", "application/json");
        if (token != null) {
            connection.setRequestProperty("Authorization", "Bearer " + token);
        }
        if (body != null) {
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", type);
            try (OutputStream output = connection.getOutputStream()) {
                output.write(body);
            }
        }

        int status = connection.getResponseCode();
        InputStream stream =
                status >= 400 ? connection.getErrorStream() : connection.getInputStream();
        String answer = stream == null ? "" : read(stream);
        connection.disconnect();
        if (status >= 400) {
            throw new IOException(
                    method + " " + strip(url) + " answered " + status + ": " + clip(answer));
        }
        try {
            return answer.isBlank() ? new JSONObject() : new JSONObject(answer);
        } catch (JSONException error) {
            throw new IOException(method + " " + strip(url) + " answered no JSON: " + clip(answer));
        }
    }

    private static String read(InputStream stream) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        for (int read; (read = stream.read(buffer)) > 0; ) {
            bytes.write(buffer, 0, read);
        }
        return bytes.toString(StandardCharsets.UTF_8.name());
    }

    /** The URL without its query, which never carries a secret here but may grow long. */
    private static String strip(String url) {
        int query = url.indexOf('?');
        return query < 0 ? url : url.substring(0, query);
    }

    private static String clip(String answer) {
        String line = answer.replace('\n', ' ');
        return line.length() > 300 ? line.substring(0, 300) + "..." : line;
    }
}
