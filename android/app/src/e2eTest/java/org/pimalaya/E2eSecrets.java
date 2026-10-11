package org.pimalaya;

import org.json.JSONObject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The credentials of the test accounts, read from the environment at run
 * time and the tokens minted from them. Nothing here is ever printed: a
 * missing variable is named, a value never is.
 */
final class E2eSecrets {
    /** A token with less than this left is minted again. */
    private static final long MARGIN_MS = 10 * 60 * 1000L;

    private static final Map<String, Token> TOKENS = new HashMap<>();

    private E2eSecrets() {}

    private static final class Token {
        final String value;
        final long expires;

        Token(String value, long expires) {
            this.value = value;
            this.expires = expires;
        }
    }

    /** One variable, null when unset or empty. */
    static String env(String name) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? null : value.trim();
    }

    /** One variable, or the default when it is unset. */
    static String env(String name, String fallback) {
        String value = env(name);
        return value == null ? fallback : value;
    }

    /** The first of the variables that is unset, null when all are there. */
    static String missing(String... names) {
        for (String name : names) {
            if (env(name) == null) {
                return name;
            }
        }
        return null;
    }

    /** Why the Google targets cannot run, null when they can. */
    static String googleMissing() {
        return env("GOOGLE_SERVICE_ACCOUNT_KEY") != null
                ? null
                : missing("GOOGLE_SERVICE_ACCOUNT_KEY_FILE");
    }

    /**
     * An access token for the Google Workspace test user, minted by the
     * service account through its domain-wide delegation: an RFC 7523 JWT
     * bearer grant whose `sub` is the user.
     */
    static synchronized String googleToken(String subject, String scope) throws Exception {
        String key = "google " + subject + " " + scope;
        Token cached = TOKENS.get(key);
        if (cached != null && cached.expires - System.currentTimeMillis() > MARGIN_MS) {
            return cached.value;
        }

        String inline = env("GOOGLE_SERVICE_ACCOUNT_KEY");
        String json =
                inline != null
                        ? inline
                        : new String(
                                Files.readAllBytes(
                                        Paths.get(env("GOOGLE_SERVICE_ACCOUNT_KEY_FILE"))),
                                StandardCharsets.UTF_8);
        JSONObject account = new JSONObject(json);
        String audience = account.optString("token_uri", "https://oauth2.googleapis.com/token");

        long now = System.currentTimeMillis() / 1000;
        JSONObject claims =
                new JSONObject()
                        .put("iss", account.getString("client_email"))
                        .put("sub", subject)
                        .put("scope", scope)
                        .put("aud", audience)
                        .put("iat", now)
                        .put("exp", now + 3600);
        String unsigned =
                base64(new JSONObject().put("alg", "RS256").put("typ", "JWT").toString())
                        + "."
                        + base64(claims.toString());
        Signature signature = Signature.getInstance("SHA256withRSA");
        signature.initSign(privateKey(account.getString("private_key")));
        signature.update(unsigned.getBytes(StandardCharsets.US_ASCII));
        String assertion =
                unsigned
                        + "."
                        + Base64.getUrlEncoder().withoutPadding().encodeToString(signature.sign());

        Map<String, String> form = new LinkedHashMap<>();
        form.put("grant_type", "urn:ietf:params:oauth:grant-type:jwt-bearer");
        form.put("assertion", assertion);
        return remember(key, E2eHttp.form(audience, form));
    }

    /** Why the Microsoft targets cannot run, null when they can. */
    static String microsoftMissing() {
        return missing("MICROSOFT_TENANT_ID", "MICROSOFT_CLIENT_ID", "MICROSOFT_CLIENT_SECRET");
    }

    /**
     * An app-only access token of the Microsoft test tenant's live-tests
     * app: an RFC 6749 client credentials grant, for a resource's
     * {@code .default} scope.
     */
    static synchronized String microsoftToken(String scope) throws Exception {
        String key = "microsoft " + scope;
        Token cached = TOKENS.get(key);
        if (cached != null && cached.expires - System.currentTimeMillis() > MARGIN_MS) {
            return cached.value;
        }

        Map<String, String> form = new LinkedHashMap<>();
        form.put("grant_type", "client_credentials");
        form.put("client_id", env("MICROSOFT_CLIENT_ID"));
        form.put("client_secret", env("MICROSOFT_CLIENT_SECRET"));
        form.put("scope", scope);
        return remember(
                key,
                E2eHttp.form(
                        "https://login.microsoftonline.com/"
                                + env("MICROSOFT_TENANT_ID")
                                + "/oauth2/v2.0/token",
                        form));
    }

    private static String remember(String key, JSONObject answer) throws IOException {
        String token = answer.optString("access_token", null);
        if (token == null) {
            throw new IOException("the token endpoint answered no access token");
        }
        long lifetime = answer.optLong("expires_in", 3600) * 1000;
        TOKENS.put(key, new Token(token, System.currentTimeMillis() + lifetime));
        return token;
    }

    private static PrivateKey privateKey(String pem) throws Exception {
        String body =
                pem.replace("-----BEGIN PRIVATE KEY-----", "")
                        .replace("-----END PRIVATE KEY-----", "")
                        .replaceAll("\\s", "");
        return KeyFactory.getInstance("RSA")
                .generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(body)));
    }

    private static String base64(String text) {
        return Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(text.getBytes(StandardCharsets.UTF_8));
    }
}
