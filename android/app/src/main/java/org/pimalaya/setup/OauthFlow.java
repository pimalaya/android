package org.pimalaya.setup;

import android.content.Intent;
import android.net.Uri;
import android.util.Log;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.net.InetAddress;
import org.json.JSONException;
import org.json.JSONObject;
import org.pimalaya.R;
import org.pimalaya.account.Oauth;
import org.pimalaya.client.Account;
import org.pimalaya.client.OauthSession;
import org.pimalaya.client.OauthTokens;
import org.pimalaya.client.PimalayaClient;
import org.pimalaya.client.Transport;
import org.pimalaya.client.ServerMetadata;
import org.pimalaya.ui.Host;
import org.pimalaya.ui.PillField;

/**
 * The connection flow's OAuth 2.0 grants: the shipped Google and
 * Microsoft clients, custom clients over a loopback or the app-scheme
 * redirect, and the zero-registration issuer path (RFC 8414 metadata
 * plus RFC 7591 dynamic registration). Every grant ends in the browser;
 * the OS-routed redirects come back through the host's onNewIntent into
 * {@link #handleRedirect}, and the redeemed tokens land back in the
 * connection flow through {@link #onConnected}. The in-flight grant
 * persists, sealed under the account key, because the browser hop
 * routinely outlives the process.
 */
public final class OauthFlow {
    /** Lands a redeemed grant back in the connection flow. */
    public interface Connector {
        void connect(
                Account account,
                String email,
                String refreshToken,
                String tokenEndpoint,
                String clientId,
                String clientSecret,
                String scope);
    }

    private final Host host;
    private final PimalayaClient client;
    private final SetupShell shell;

    /** The in-flight grant as persisted, sealed across the browser hop. */
    private final PendingGrantStore grants;

    /** Wired by the host after construction (the two flows reference
     *  each other, so one side binds late). */
    public Connector onConnected;

    /** Resets the config screen's continue when a grant aborts. */
    public Runnable onAborted;

    /** Puts the running step's button on its loader while a grant starts. */
    public Runnable onBusy;

    /** The in-flight grant: the PKCE session and the account material
     *  the redeem needs (all null or empty when no grant runs). */
    private OauthSession pendingOauth;

    private String pendingTokenEndpoint;
    private String pendingBaseUrl;
    private String pendingAccountEmail;
    private String pendingClientId;
    private String pendingClientSecret;

    /**
     * The issuer an issuer-flow grant started at, which its redirect must not
     * contradict (RFC 9207); null for the shipped and custom clients.
     */
    private String pendingIssuer;

    /** The redirect URI the in-flight grant waits on. */
    private String pendingRedirectUri;

    /** When the in-flight grant started, in wall-clock milliseconds. */
    private long pendingSince;

    /** How long an in-flight grant waits before it is abandoned. */
    static final long GRANT_TTL_MS = 15 * 60 * 1000;

    /** How long a loopback connection may take to send its request line. */
    private static final int LOOPBACK_READ_TIMEOUT_MS = 10_000;

    /** The loopback redirect listener, closed with the activity. */
    private java.net.ServerSocket loopbackServer;

    public OauthFlow(Host host, PimalayaClient client, SetupShell shell) {
        this.host = host;
        this.client = client;
        this.shell = shell;
        this.grants = new PendingGrantStore(host);
    }

    /**
     * Starts a Google OAuth grant with the app's own client, for whatever
     * the scope covers (mail, calendars, CardDAV or the People API):
     * prepares the PKCE session, then opens the authorization URL in the
     * browser. The redirect comes back on the reversed-client-id custom
     * scheme.
     */
    void startGoogleOauth(String email, String scope, String baseUrl) {
        pendingOauth =
                new OauthSession(Oauth.GOOGLE_CLIENT_ID, null, Oauth.GOOGLE_REDIRECT_URI, scope);
        pendingTokenEndpoint = Oauth.GOOGLE_TOKEN_ENDPOINT;
        pendingBaseUrl = baseUrl;
        pendingAccountEmail = email;
        pendingClientId = Oauth.GOOGLE_CLIENT_ID;
        pendingClientSecret = null;
        pendingIssuer = null;

        // NOTE: access_type=offline + prompt=consent make Google issue a
        // refresh token; include_granted_scopes keeps what the account
        // granted the app before (incremental authorization), so the new
        // token covers it too; login_hint preselects the entered account.
        JSONObject extras = new JSONObject();
        try {
            extras.put("access_type", "offline");
            extras.put("prompt", "consent");
            extras.put("include_granted_scopes", "true");
            if (email.contains("@")) {
                extras.put("login_hint", email);
            }
        } catch (JSONException ignored) {
        }

        try {
            String url = pendingOauth.authorizeUrl(Oauth.GOOGLE_AUTH_ENDPOINT, extras);
            persistPendingOauth(Oauth.GOOGLE_CLIENT_ID, Oauth.GOOGLE_REDIRECT_URI);
            onBusy.run();
            browse(url);
        } catch (Exception error) {
            abort(error);
        }
    }

    /**
     * Starts a Microsoft OAuth grant with the app's own client, mirroring
     * the Google one: PKCE session, browser, redirect on the pimalaya
     * custom scheme. The scope addresses one API, Outlook's for mail or
     * Graph's for contacts and calendars, Entra issuing a token for one.
     */
    void startMicrosoftOauth(String email, String scope, String baseUrl) {
        pendingOauth =
                new OauthSession(
                        Oauth.MICROSOFT_CLIENT_ID, null, Oauth.MICROSOFT_REDIRECT_URI, scope);
        pendingTokenEndpoint = Oauth.MICROSOFT_TOKEN_ENDPOINT;
        pendingBaseUrl = baseUrl;
        pendingAccountEmail = email;
        pendingClientId = Oauth.MICROSOFT_CLIENT_ID;
        pendingClientSecret = null;
        pendingIssuer = null;

        // NOTE: the refresh token comes from the offline_access scope,
        // no extra parameter needed; login_hint preselects the account.
        JSONObject extras = new JSONObject();
        try {
            if (email.contains("@")) {
                extras.put("login_hint", email);
            }
        } catch (JSONException ignored) {
        }

        try {
            String url = pendingOauth.authorizeUrl(Oauth.MICROSOFT_AUTH_ENDPOINT, extras);
            persistPendingOauth(Oauth.MICROSOFT_CLIENT_ID, Oauth.MICROSOFT_REDIRECT_URI);
            onBusy.run();
            browse(url);
        } catch (Exception error) {
            abort(error);
        }
    }

    /**
     * Whether an intent's URL is the pending grant's redirect: the redirect
     * URI exactly, up to its query, and the grant's state in that query.
     */
    static boolean isRedirectOf(String url, String redirectUri, String state) {
        if (redirectUri == null || redirectUri.isEmpty() || state == null || state.isEmpty()) {
            return false;
        }
        int end = url.length();
        for (char mark : new char[] {'?', '#'}) {
            int at = url.indexOf(mark);
            if (at >= 0 && at < end) {
                end = at;
            }
        }
        // NOTE: Entra answers an authority-only redirect URI with an empty
        // path as `/` (pimalaya://oauth2redirect/?code=...), so an empty
        // path and `/` name the same redirect.
        if (!withoutRootSlash(url.substring(0, end)).equals(withoutRootSlash(redirectUri))) {
            return false;
        }
        try {
            return state.equals(Uri.parse(url).getQueryParameter("state"));
        } catch (UnsupportedOperationException opaque) {
            return false;
        }
    }

    /** A redirect URI with the lone `/` path of a `scheme://authority/` form dropped. */
    private static String withoutRootSlash(String uri) {
        int authority = uri.indexOf("://");
        if (authority >= 0 && uri.endsWith("/") && uri.indexOf('/', authority + 3) == uri.length() - 1) {
            return uri.substring(0, uri.length() - 1);
        }
        return uri;
    }

    /** Whether a grant started at {@code since} was abandoned by {@code now}. */
    static boolean expired(long since, long now) {
        return now - since > GRANT_TTL_MS || now < since;
    }

    /**
     * The scopes a redeemed grant holds: those the token response names
     * (RFC 6749 section 5.1), else those the request asked for, else null
     * when neither is known (a grant restored after the process died).
     */
    private static String grantedScope(OauthTokens tokens, OauthSession session) {
        if (tokens.scope != null && !tokens.scope.isEmpty()) {
            return tokens.scope;
        }
        String asked = session.scope();
        return asked == null || asked.isEmpty() ? null : asked;
    }

    /**
     * Redeems the OAuth redirect for tokens and connects with them, the
     * access token standing in for the password (empty login, Bearer
     * auth). No-op unless a grant is in flight for this redirect,
     * restoring a persisted one when the process died while the user
     * was in the browser.
     */
    public void handleRedirect(Intent intent) {
        Uri data = intent == null ? null : intent.getData();
        if (data == null) {
            return;
        }
        if (pendingOauth == null || expired(pendingSince, System.currentTimeMillis())) {
            pendingOauth = null;
            restorePendingOauth();
        }
        if (pendingOauth == null) {
            return;
        }

        // NOTE: any app can send this activity a VIEW intent; only the
        // grant's own redirect URI carrying the grant's state redeems (and
        // so ends) it, anything else leaves the grant waiting.
        String redirectUrl = data.toString();
        if (!isRedirectOf(redirectUrl, pendingRedirectUri, pendingOauth.state())) {
            Log.w("pimalaya", "ignoring an intent that is not this grant's redirect");
            return;
        }

        OauthSession session = pendingOauth;
        String tokenEndpoint = pendingTokenEndpoint;
        String baseUrl = pendingBaseUrl;
        String email = pendingAccountEmail;
        String clientId = pendingClientId;
        String clientSecret = pendingClientSecret;
        String issuer = pendingIssuer;
        pendingOauth = null;
        clearPendingOauth();

        // NOTE: the one-time code is redacted; everything else in the
        // redirect is needed verbatim to debug rejections.
        Log.d(
                "pimalaya",
                "oauth redirect: " + redirectUrl.replaceAll("code=[^&]*", "code=<redacted>"));

        host.io.execute(
                () -> {
                    try {
                        OauthTokens tokens = session.redeem(tokenEndpoint, redirectUrl, issuer);
                        // NOTE: not postAlive: the redeemed grant persists the account
                        // (finishOnboarding), which a destroyed activity must not drop.
                        host.main.post(
                                () ->
                                        onConnected.connect(
                                                new Account(baseUrl, "", tokens.accessToken),
                                                email,
                                                tokens.refreshToken,
                                                tokenEndpoint,
                                                clientId,
                                                clientSecret,
                                                grantedScope(tokens, session)));
                    } catch (Exception error) {
                        Log.w("pimalaya", "oauth redeem failed", error);
                        host.postAlive(
                                () -> {
                                    onAborted.run();
                                    host.showError(error, R.string.connect_failed);
                                });
                    }
                });
    }

    /**
     * Opens the OAuth client page: the client ID and its optional secret
     * empty, the endpoints, the scope and the redirect prefilled with the
     * best-known values. Continue runs the custom grant against the given
     * redirect URI: an http loopback URL serves the redirect on a local
     * listener, and the app's own org.pimalaya:/oauth2redirect scheme rides
     * the OS intent route instead (for servers that reject loopback
     * redirects). Back returns to the sign-in page with nothing lost.
     *
     * @param heading what the client is for, the page's first line
     * @param own whether the user asked for it, rather than the server
     *     refusing to register one
     */
    void promptOauthClient(
            String email,
            String baseUrl,
            String authEndpoint,
            String tokenEndpoint,
            String scope,
            String resource,
            String heading,
            boolean own) {
        ((TextView) host.findViewById(R.id.oauth_line)).setText(heading);
        ((TextView) host.findViewById(R.id.oauth_message))
                .setText(own ? R.string.oauth_message_own : R.string.oauth_message);

        PillField clientId = PillField.of(host, R.string.oauth_client_id, "", false, false);
        PillField clientSecret =
                PillField.of(host, R.string.oauth_client_secret, "", true, false);
        PillField authField =
                PillField.of(host, R.string.oauth_authorization_endpoint, authEndpoint, false, false);
        PillField tokenField =
                PillField.of(host, R.string.oauth_token_endpoint, tokenEndpoint, false, false);
        PillField scopeField = PillField.of(host, R.string.oauth_scope, scope, false, false);
        PillField redirectField =
                PillField.of(
                        host,
                        R.string.oauth_redirect,
                        "http://127.0.0.1:" + freePort(),
                        false,
                        false);

        LinearLayout container = host.findViewById(R.id.oauth_container);
        container.removeAllViews();
        for (PillField field :
                new PillField[] {
                    clientId, clientSecret, authField, tokenField, scopeField, redirectField
                }) {
            container.addView(field.view);
        }

        android.widget.Button submit = host.findViewById(R.id.oauth_continue);
        Runnable ready = () -> host.setFabEnabled(R.id.oauth_continue, !clientId.text().isEmpty());
        clientId.input.addTextChangedListener(
                new android.text.TextWatcher() {
                    @Override
                    public void beforeTextChanged(CharSequence s, int a, int b, int c) {}

                    @Override
                    public void onTextChanged(CharSequence s, int a, int b, int c) {}

                    @Override
                    public void afterTextChanged(android.text.Editable s) {
                        ready.run();
                    }
                });
        submit.setOnClickListener(
                view -> {
                    host.hideKeyboard();
                    String id = clientId.text();
                    String secret = clientSecret.text();
                    String auth = authField.text();
                    String token = tokenField.text();
                    String scopes = scopeField.text();
                    String redirect = redirectField.text();

                    if (redirect.startsWith("http://")) {
                        startLoopbackOauth(
                                email, id, secret, auth, token, scopes, baseUrl, redirect,
                                resource);
                    } else if (redirect.startsWith("org.pimalaya:")) {
                        launchSchemeGrant(
                                email,
                                baseUrl,
                                id,
                                secret.isEmpty() ? null : secret,
                                auth,
                                token,
                                scopes,
                                resource,
                                null);
                    } else {
                        host.toast(host.getString(R.string.oauth_redirect_invalid));
                    }
                });

        shell.showAuth(OnboardingFlow.STEP_OAUTH);
        host.setAuthLoading(R.id.oauth_continue, R.id.oauth_progress, false);
        ready.run();
    }

    /**
     * Runs the zero-configuration OAuth path from a bare issuer: fetch
     * its RFC 8414 metadata, register a public client dynamically
     * (RFC 7591) when the server allows it, then drive the ordinary
     * code grant with the freshly issued client id over the OS-routed
     * custom-scheme redirect (the servers that support registration,
     * fastmail and Stalwart, reject a loopback http redirect but accept
     * the private-use scheme, RFC 8252 §7.1). When the server publishes
     * no registration endpoint, or the user asked for their own client
     * ({@code own}), opens the OAuth client page with the endpoints
     * prefilled, so a self-registered client id can be pasted instead.
     */
    void startIssuerOauth(
            String email,
            String baseUrl,
            String issuer,
            String resource,
            String domains,
            String heading,
            boolean own) {
        onBusy.run();

        host.io.execute(
                () -> {
                    try {
                        ServerMetadata metadata;
                        try (Transport transport = new Transport()) {
                            metadata = client.oauthServerMetadata(transport, issuer);
                        }
                        if (metadata.authorizationEndpoint == null
                                || metadata.tokenEndpoint == null) {
                            throw new IllegalStateException(
                                    host.getString(R.string.oauth_metadata_incomplete));
                        }

                        if (own || !metadata.supportsDynamicRegistration()) {
                            // No dynamic registration, or the user's own
                            // client: endpoints prefilled.
                            host.postAlive(
                                    () -> {
                                        onAborted.run();
                                        promptOauthClient(
                                                email,
                                                baseUrl,
                                                metadata.authorizationEndpoint,
                                                metadata.tokenEndpoint,
                                                metadata.domainScope(domains),
                                                resource,
                                                heading,
                                                own);
                                    });
                            return;
                        }

                        // NOTE: request only the scopes of the domains this
                        // grant covers (plus offline_access), not the
                        // server's whole set: asking for its extras earns an
                        // invalid_scope authorization error, and asking for
                        // the wrong domain's puts the wrong thing on the
                        // consent screen.
                        String scope = metadata.domainScope(domains);
                        String clientId;
                        try (Transport transport = new Transport()) {
                            clientId =
                                    client.oauthRegisterClient(
                                            transport,
                                            metadata.registrationEndpoint,
                                            Oauth.REDIRECT_URI,
                                            host.getString(R.string.app_name),
                                            scope);
                        }
                        Log.d(
                                "pimalaya",
                                "issuer grant: client "
                                        + clientId
                                        + ", scope \""
                                        + scope
                                        + "\", authorize "
                                        + metadata.authorizationEndpoint
                                        + ", token "
                                        + metadata.tokenEndpoint);

                        host.postAlive(
                                () ->
                                        launchSchemeGrant(
                                                email,
                                                baseUrl,
                                                clientId,
                                                null,
                                                metadata.authorizationEndpoint,
                                                metadata.tokenEndpoint,
                                                scope,
                                                resource,
                                                metadata.issuer));
                    } catch (Exception error) {
                        Log.w("pimalaya", "issuer oauth failed", error);
                        host.postAlive(
                                () -> {
                                    onAborted.run();
                                    host.showError(error, R.string.connect_failed);
                                });
                    }
                });
    }

    /** Closes the loopback listener, if one is open. */
    public void closeLoopback() {
        java.net.ServerSocket server = loopbackServer;
        loopbackServer = null;
        if (server != null) {
            try {
                server.close();
            } catch (java.io.IOException ignored) {
            }
        }
    }

    /**
     * Opens the browser for a grant riding the app's own
     * org.pimalaya:/oauth2redirect scheme: PKCE session over
     * the OS-routed redirect, persisted so it survives the process,
     * redeemed in {@link #handleRedirect}. Used by the
     * zero-registration issuer flow (no secret) and by custom clients
     * whose redirect is the app scheme (for servers that reject a
     * loopback redirect).
     */
    void launchSchemeGrant(
            String email,
            String baseUrl,
            String clientId,
            String clientSecret,
            String authEndpoint,
            String tokenEndpoint,
            String scope,
            String resource,
            String issuer) {
        pendingOauth = new OauthSession(clientId, clientSecret, Oauth.REDIRECT_URI, scope);
        pendingIssuer = issuer;
        pendingTokenEndpoint = tokenEndpoint;
        pendingBaseUrl = baseUrl;
        pendingAccountEmail = email;
        pendingClientId = clientId;
        pendingClientSecret = clientSecret;

        // NOTE: the RFC 8707 resource is required: fastmail bounces a
        // request without one as invalid_target. Google's
        // access_type/prompt hints stay off this flow: refresh comes
        // from offline_access, and unknown parameters risk an
        // invalid_request bounce from strict servers.
        JSONObject extras = new JSONObject();
        try {
            if (resource != null && !resource.isEmpty()) {
                extras.put("resource", resource);
            }
            if (email.contains("@")) {
                extras.put("login_hint", email);
            }
        } catch (JSONException ignored) {
        }

        try {
            String url = pendingOauth.authorizeUrl(authEndpoint, extras);
            Log.d("pimalaya", "scheme grant authorize url: " + url);
            persistPendingOauth(clientId, Oauth.REDIRECT_URI);
            onBusy.run();
            browse(url);
        } catch (Exception error) {
            abort(error);
        }
    }

    /**
     * Runs a custom-client grant against an http loopback redirect URI:
     * binds its port, opens the browser, and serves the redirect the
     * browser lands on (its path and state, see {@link LoopbackRedirect}),
     * reconstructing its URL to redeem for tokens. The redirect host is the
     * 127.0.0.1 literal (RFC 8252 section 8.3): a typed localhost may
     * resolve to ::1 first, where another app could listen. The listener
     * runs on a thread of its own, never the io executor, until the
     * redirect arrives or the wait times out; it dies with the process (a
     * custom grant is not persisted, having no OS-routed redirect to resume
     * through).
     */
    private void startLoopbackOauth(
            String email,
            String clientId,
            String clientSecret,
            String authEndpoint,
            String tokenEndpoint,
            String scope,
            String baseUrl,
            String redirectUri,
            String resource) {
        String secret = clientSecret.isEmpty() ? null : clientSecret;

        Uri redirect = Uri.parse(redirectUri);
        if (!"127.0.0.1".equals(redirect.getHost())) {
            host.toast(host.getString(R.string.oauth_redirect_invalid));
            return;
        }
        int port = redirect.getPort() == -1 ? 80 : redirect.getPort();
        String path =
                redirect.getPath() == null || redirect.getPath().isEmpty()
                        ? "/"
                        : redirect.getPath();

        // NOTE: origin the browser's request target is appended to when
        // rebuilding the redirect URL; the typed URI may carry a path.
        String origin = "http://127.0.0.1" + (redirect.getPort() == -1 ? "" : ":" + port);

        java.net.ServerSocket server;
        try {
            server = new java.net.ServerSocket(port, 1, InetAddress.getByName("127.0.0.1"));
            server.setSoTimeout(300_000);
        } catch (Exception error) {
            host.showError(error, R.string.oauth_port_taken);
            return;
        }
        loopbackServer = server;

        OauthSession session = new OauthSession(clientId, secret, redirectUri, scope);
        String done =
                "<html><body style='font-family:sans-serif;text-align:center;"
                        + "margin-top:4em'>"
                        + host.getString(R.string.oauth_loopback_done)
                        + "</body></html>";

        // NOTE: access_type=offline + prompt=consent make Google-style
        // servers issue a refresh token (ignored by servers that do not
        // use them); login_hint preselects the account.
        JSONObject extras = new JSONObject();
        try {
            extras.put("access_type", "offline");
            extras.put("prompt", "consent");
            if (email.contains("@")) {
                extras.put("login_hint", email);
            }
        } catch (JSONException ignored) {
        }

        Thread listener =
                new Thread(
                        () -> {
                            try {
                                String authUrl = session.authorizeUrl(authEndpoint, extras);
                                host.postAlive(
                                        () -> {
                                            onBusy.run();
                                            try {
                                                browse(authUrl);
                                            } catch (IllegalStateException refused) {
                                                closeLoopback();
                                                onAborted.run();
                                                host.showError(refused, R.string.connect_failed);
                                            }
                                        });

                                String redirectUrl =
                                        LoopbackRedirect.await(
                                                server,
                                                origin,
                                                path,
                                                session.state(),
                                                LOOPBACK_READ_TIMEOUT_MS,
                                                done);
                                OauthTokens tokens = session.redeem(tokenEndpoint, redirectUrl);
                                // NOTE: not postAlive: the redeemed grant persists the account
                                // (finishOnboarding), which a destroyed activity must not drop.
                                host.main.post(
                                        () ->
                                                onConnected.connect(
                                                        new Account(
                                                                baseUrl, "", tokens.accessToken),
                                                        email,
                                                        tokens.refreshToken,
                                                        tokenEndpoint,
                                                        clientId,
                                                        secret,
                                                        grantedScope(tokens, session)));
                            } catch (Exception error) {
                                Log.w("pimalaya", "custom oauth failed", error);
                                host.postAlive(
                                        () -> {
                                            onAborted.run();
                                            host.showError(error, R.string.connect_failed);
                                        });
                            } finally {
                                closeLoopback();
                            }
                        },
                        "oauth-loopback");
        listener.setDaemon(true);
        listener.start();
    }

    /**
     * Persists the in-flight grant: the browser hop routinely outlives
     * the process (the system reclaims backgrounded apps), and without
     * the state and PKCE verifier the redirect could not be redeemed.
     */
    private void persistPendingOauth(String clientId, String redirectUri) throws Exception {
        pendingRedirectUri = redirectUri;
        pendingSince = System.currentTimeMillis();
        grants.save(
                new JSONObject()
                        .put("clientId", clientId)
                        .put("clientSecret", pendingClientSecret == null ? "" : pendingClientSecret)
                        .put("redirectUri", redirectUri)
                        .put("state", pendingOauth.state())
                        .put("verifier", pendingOauth.pkceVerifier())
                        .put("tokenEndpoint", pendingTokenEndpoint)
                        .put("baseUrl", pendingBaseUrl)
                        .put("email", pendingAccountEmail)
                        .put("issuer", pendingIssuer == null ? "" : pendingIssuer)
                        .put(PendingGrantStore.SAVED_AT, pendingSince));
    }

    /** Restores the persisted grant into the pending fields, if any. */
    private void restorePendingOauth() {
        JSONObject grant = grants.load();
        if (grant == null || grant.optString("state").isEmpty()) {
            return;
        }

        String secret = grant.optString("clientSecret");
        pendingOauth =
                new OauthSession(
                        grant.optString("clientId"),
                        secret,
                        grant.optString("redirectUri"),
                        "",
                        grant.optString("state"),
                        grant.optString("verifier"));
        pendingTokenEndpoint = grant.optString("tokenEndpoint");
        pendingBaseUrl = grant.optString("baseUrl");
        pendingAccountEmail = grant.optString("email");
        pendingClientId = grant.optString("clientId");
        pendingClientSecret = secret.isEmpty() ? null : secret;
        String issuer = grant.optString("issuer");
        pendingIssuer = issuer.isEmpty() ? null : issuer;
        pendingRedirectUri = grant.optString("redirectUri");
        pendingSince = grant.optLong(PendingGrantStore.SAVED_AT);
    }

    /** Drops the persisted grant (redeemed, cancelled or aborted). */
    private void clearPendingOauth() {
        grants.clear();
    }

    /**
     * The user left the grant behind (closed or stepped back out of the
     * connection flow): forget it, so a late redirect redeems nothing.
     */
    public void cancel() {
        pendingOauth = null;
        clearPendingOauth();
    }

    /** A grant that never reached the browser: drop it and report. */
    private void abort(Exception error) {
        pendingOauth = null;
        clearPendingOauth();
        onAborted.run();
        host.showError(error, R.string.connect_failed);
    }

    /**
     * Opens an authorization URL in the browser, https only: a plain one
     * would carry the request in clear, and any other scheme is another
     * app's to handle, which hostile metadata could aim anywhere. Plain http
     * is left to a loopback host, a custom client's own test server.
     */
    private void browse(String url) {
        Uri uri = Uri.parse(url);
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(java.util.Locale.ROOT);
        String authority = uri.getHost() == null ? "" : uri.getHost();
        boolean loopback =
                authority.equals("127.0.0.1") || authority.equals("localhost") || authority.equals("[::1]");
        if (!scheme.equals("https") && !(scheme.equals("http") && loopback)) {
            throw new IllegalStateException("Refused to open a non-https authorization URL");
        }
        host.startActivity(new Intent(Intent.ACTION_VIEW, uri));
    }

    /** A free loopback TCP port, for the redirect prefill. */
    private static int freePort() {
        try (java.net.ServerSocket probe =
                new java.net.ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
            return probe.getLocalPort();
        } catch (Exception error) {
            // NOTE: fallback in the ephemeral range; the bind at grant
            // time surfaces a clash as an error.
            return 8117;
        }
    }
}
