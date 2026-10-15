package org.pimalaya.client;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.pimalaya.client.ReplyShape.assertMirrors;
import static org.pimalaya.client.ReplyShape.keys;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

/**
 * Pins the account records on the real bridge, against captured literals.
 *
 * <p>Backend info, merged configs, PKCE authorization URLs. OAuth endpoints
 * need https (rs/account/oauth.rs {@code require_https}), so token, metadata
 * and registration replies are literals shaped as io-oauth serializes them.
 */
@RunWith(RobolectricTestRunner.class)
public class AccountClientTest {
    private static final String TOKEN = "https://auth.example.org/token";

    private static final String STATE = "s7a7e0000000000000000000000000ab";

    private static final String VERIFIER =
            "v3r1f13r00000000000000000000000000000000000000000000000000000cd";

    /** The configs of one mechanism each, as io-pim-discovery writes them. */
    private static final String CONFIGS =
            "["
                    + "{\"service\":\"imap\",\"endpoint\":{\"tcp\":{\"host\":\"imap.example.org\",\"port\":993,\"security\":\"tls\"}},\"username\":\"jane\",\"auth\":[\"password\",{\"oauthAuthorizationCodeGrant\":{\"authorizationEndpoint\":\"https://auth.example.org/authorize\",\"tokenEndpoint\":\"https://auth.example.org/token\",\"scope\":\"mail\"}}],\"source\":\"pacc\",\"resolved\":false},"
                    + "{\"service\":\"jmap\",\"endpoint\":{\"http\":\"https://api.example.org/jmap/session\"},\"username\":null,\"auth\":[{\"oauthIssuer\":\"https://api.example.org\"},\"bearer\"],\"source\":\"jmap\",\"resolved\":false},"
                    + "{\"service\":\"carddav\",\"endpoint\":{\"http\":\"https://dav.example.org/\"},\"username\":null,\"auth\":[{\"oauthDeviceAuthorizationGrant\":{\"deviceAuthorizationEndpoint\":\"https://auth.example.org/device\",\"tokenEndpoint\":\"https://auth.example.org/token\",\"scope\":null}}],\"source\":\"dav\",\"resolved\":true},"
                    + "{\"service\":\"gmail\",\"endpoint\":{\"http\":\"https://gmail.googleapis.com/\"},\"username\":null,\"auth\":[{\"oauthAuthorizationCodeGrant\":{\"authorizationEndpoint\":\"https://accounts.google.com/o/oauth2/v2/auth\",\"tokenEndpoint\":\"https://oauth2.googleapis.com/token\",\"scope\":null}}],\"source\":\"provider\",\"resolved\":false,\"provider\":\"google\"},"
                    + "{\"service\":\"smtp\",\"endpoint\":{\"tcp\":{\"host\":\"smtp.example.org\",\"port\":587,\"security\":\"starttls\"}},\"username\":\"jane@example.org\",\"auth\":[\"password\"],\"source\":\"ispMain\",\"resolved\":false}"
                    + "]";

    /** What {@link #CONFIGS} parse to. */
    private static final List<ServiceConfig> PARSED =
            List.of(
                    new ServiceConfig(
                            "imap",
                            null,
                            "imap.example.org",
                            993,
                            "tls",
                            "jane",
                            List.of(
                                    AuthMethod.password(),
                                    AuthMethod.codeGrant(
                                            "https://auth.example.org/authorize", TOKEN, "mail")),
                            "pacc"),
                    new ServiceConfig(
                            "jmap",
                            "https://api.example.org/jmap/session",
                            null,
                            0,
                            null,
                            null,
                            List.of(AuthMethod.issuer("https://api.example.org"), AuthMethod.bearer()),
                            "jmap"),
                    new ServiceConfig(
                            "carddav",
                            "https://dav.example.org/",
                            null,
                            0,
                            null,
                            null,
                            List.of(
                                    AuthMethod.deviceGrant(
                                            "https://auth.example.org/device", TOKEN, null)),
                            "dav"),
                    new ServiceConfig(
                            "gmail",
                            "https://gmail.googleapis.com/",
                            null,
                            0,
                            null,
                            null,
                            List.of(
                                    AuthMethod.codeGrant(
                                            "https://accounts.google.com/o/oauth2/v2/auth",
                                            "https://oauth2.googleapis.com/token",
                                            null)),
                            "provider:google"),
                    new ServiceConfig(
                            "smtp",
                            null,
                            "smtp.example.org",
                            587,
                            "starttls",
                            "jane@example.org",
                            List.of(AuthMethod.password()),
                            "ispMain"));

    @Test
    public void everyBackendAnswersTheFactsShape() {
        List<String> urls =
                List.of(
                        AccountClient.googleBase("jane@gmail.com"),
                        AccountClient.msgraphBase("jane@outlook.com"),
                        AccountClient.jmapBase("https://api.fastmail.com/jmap/session"),
                        "https://dav.example.org/",
                        "imaps://imap.example.org:993",
                        "",
                        "local://on-this-device");

        for (String url : urls) {
            assertMirrors(BackendInfo.class, Native.accountInfo(url));
        }
        assertEquals(new Account("", "", "").info(), new Account(null, "", "").info());
    }

    @Test
    public void anUnknownProtocolIsUnreadable() {
        // NOTE: strict on purpose: an unknown family would read as none.
        assertThrows(
                PimalayaException.class,
                () -> BackendInfo.from(
                        new JSONObject(
                                "{\"cardFetch\":\"multiget\",\"cardPush\":\"parallel\","
                                        + "\"host\":\"\",\"mailStep\":\"store\","
                                        + "\"protocol\":\"pop3\",\"sessions\":3}")));
    }

    @Test
    public void configsParseFlattenedAfterTheBridgeMerge() throws Exception {
        String merged = Native.searchMerge("[" + CONFIGS + "]");
        JSONArray reply = new JSONArray(merged);
        List<ServiceConfig> configs = new ArrayList<>();
        for (int index = 0; index < reply.length(); index++) {
            configs.add(ServiceConfig.from(reply.getJSONObject(index)));
        }
        assertEquals(PARSED, configs);

        JSONArray literal = new JSONArray(CONFIGS);
        for (int index = 0; index < literal.length(); index++) {
            assertEquals(PARSED.get(index), ServiceConfig.from(literal.getJSONObject(index)));
        }
    }

    @Test
    public void tokensParseWithTheirDefaults() throws Exception {
        String full =
                "{\"access_token\":\"a\",\"token_type\":\"Bearer\",\"expires_in\":3600,\"refresh_token\":\"r\",\"scope\":\"mail\",\"issued_at\":1760000000}";
        assertEquals(
                new OauthTokens("a", "Bearer", 3600, "r", "mail", 1760000000L),
                OauthTokens.from(new JSONObject(full)));
        assertEquals(
                new OauthTokens("a", "bearer", 0, null, null, 0),
                OauthTokens.from(
                        new JSONObject(
                                "{\"access_token\":\"a\",\"token_type\":\"bearer\",\"expires_in\":null,\"refresh_token\":null,\"scope\":null,\"issued_at\":null}")));
        assertEquals(
                new OauthTokens("a", "Bearer", 0, null, null, 0),
                OauthTokens.from(new JSONObject("{\"access_token\":\"a\"}")));

        List<String> snake = new ArrayList<>();
        for (var component : OauthTokens.class.getRecordComponents()) {
            snake.add(component.getName().replaceAll("([A-Z])", "_$1").toLowerCase());
        }
        assertEquals(snake, keys(full));
    }

    @Test
    public void serverMetadataKeepsTheEndpointsAndJoinsTheScopes() throws Exception {
        ServerMetadata full =
                ServerMetadata.from(
                        new JSONObject(
                                "{\"issuer\":\"https://api.example.org\",\"authorization_endpoint\":\"https://api.example.org/authorize\",\"token_endpoint\":\"https://api.example.org/token\",\"registration_endpoint\":\"https://api.example.org/register\",\"scopes_supported\":[\"openid\",\"urn:ietf:params:jmap:mail\",\"offline_access\"]}"));
        assertEquals(
                new ServerMetadata(
                        "https://api.example.org",
                        "https://api.example.org/authorize",
                        "https://api.example.org/token",
                        "https://api.example.org/register",
                        "openid urn:ietf:params:jmap:mail offline_access"),
                full);
        assertEquals(true, full.supportsDynamicRegistration());
        assertEquals("offline_access", full.domainScope("mail contacts"));

        ServerMetadata closed =
                ServerMetadata.from(
                        new JSONObject(
                                "{\"issuer\":\"https://api.example.org\",\"authorization_endpoint\":\"https://api.example.org/authorize\",\"token_endpoint\":\"https://api.example.org/token\",\"registration_endpoint\":null,\"scopes_supported\":null}"));
        assertEquals(
                new ServerMetadata(
                        "https://api.example.org",
                        "https://api.example.org/authorize",
                        "https://api.example.org/token",
                        null,
                        ""),
                closed);
        assertEquals(false, closed.supportsDynamicRegistration());
        assertEquals(
                "urn:ietf:params:oauth:scope:mail urn:ietf:params:oauth:scope:contacts offline_access",
                closed.domainScope("mail contacts"));

        ServerMetadata blank = ServerMetadata.from(new JSONObject("{\"registration_endpoint\":\"\"}"));
        assertEquals(new ServerMetadata(null, null, null, "", ""), blank);
        assertEquals(false, blank.supportsDynamicRegistration());
        assertEquals(new ServerMetadata(null, null, null, null, ""), ServerMetadata.from(new JSONObject("{}")));
    }

    @Test
    public void theAuthorizationUrlCarriesTheExtras() {
        OauthSession session =
                OauthSession.resume(
                        new OauthRequest(
                                "client", "sec", "org.pimalaya:/oauth2redirect", "mail offline_access"),
                        STATE,
                        VERIFIER);
        String base =
                "https://auth.example.org/authorize?";
        String pkce = "client_id=client&code_challenge=qRAirwBKQmFLiHBJrXp4xmqWE7MGIh0ADJFt3NPudFc&code_challenge_method=S256&";
        String tail =
                "redirect_uri=org.pimalaya%3A%2Foauth2redirect&response_type=code&scope=mail+offline_access&state="
                        + STATE;

        Map<String, String> google = new LinkedHashMap<>();
        google.put("access_type", "offline");
        google.put("prompt", "consent");
        google.put("include_granted_scopes", "true");
        google.put("login_hint", "jane@gmail.com");
        assertEquals(
                base
                        + "access_type=offline&"
                        + pkce
                        + "include_granted_scopes=true&login_hint=jane%40gmail.com&prompt=consent&"
                        + tail,
                session.authorizeUrl("https://auth.example.org/authorize", google));

        Map<String, String> scheme = new LinkedHashMap<>();
        scheme.put("resource", "https://api.example.org/jmap/session");
        scheme.put("login_hint", "jane@example.org");
        assertEquals(
                base
                        + pkce
                        + "login_hint=jane%40example.org&redirect_uri=org.pimalaya%3A%2Foauth2redirect&resource=https%3A%2F%2Fapi.example.org%2Fjmap%2Fsession&response_type=code&scope=mail+offline_access&state="
                        + STATE,
                session.authorizeUrl("https://auth.example.org/authorize", scheme));

        assertEquals(base + pkce + tail, session.authorizeUrl("https://auth.example.org/authorize", Map.of()));
        assertEquals(base + pkce + tail, session.authorizeUrl("https://auth.example.org/authorize", null));

        OauthSession blank =
                OauthSession.resume(
                        new OauthRequest("client", null, "http://127.0.0.1:8117", null), "s", "v");
        assertEquals("", blank.scope());
        assertEquals(
                "https://auth.example.org/authorize?client_id=client&code_challenge=TJRIXgwhrmxBzh3-e2v6zupato5AokdvUCCOUm9QYIA&code_challenge_method=S256&redirect_uri=http%3A%2F%2F127.0.0.1%3A8117&response_type=code&state=s",
                blank.authorizeUrl("https://auth.example.org/authorize", Map.of()));
    }

    @Test
    public void aStartedSessionHoldsFreshPkceMaterial() {
        OauthSession session =
                OauthSession.start(
                        new OauthRequest("client", null, "org.pimalaya:/oauth2redirect", "mail"));

        assertEquals(32, session.state().length());
        assertEquals(64, session.pkceVerifier().length());
        assertEquals("mail", session.scope());
    }
}
