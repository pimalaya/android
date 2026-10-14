package org.pimalaya.setup;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import java.util.Set;
import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.pimalaya.FakeAndroidKeyStore;
import org.pimalaya.MainActivity;
import org.pimalaya.account.Oauth;
import org.pimalaya.account.SecureStore;
import org.pimalaya.client.AccountClient;
import org.pimalaya.client.OauthRequest;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

/**
 * The in-flight OAuth grant on disk: sealed under the account key, never
 * in clear, and gone once the grant ends however it ends.
 */
@RunWith(RobolectricTestRunner.class)
public class PendingGrantTest {
    private static final String PREFS = "pimalaya.oauth";
    private static final String STATE = "s7a7e0000000000000000000000000ab";
    private static final String VERIFIER = "v3r1f13r00000000000000000000000000000000000000000000000000000cd";
    private static final String SECRET = "cl13nt-s3cr3t";

    private Context context;

    @Before
    public void setUp() {
        FakeAndroidKeyStore.install();
        context = RuntimeEnvironment.getApplication();
    }

    private SharedPreferences prefs() {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** Every raw value on disk, concatenated, to search for a leak. */
    private String raw() {
        StringBuilder all = new StringBuilder();
        for (Object value : prefs().getAll().values()) {
            all.append(value).append('\n');
        }
        return all.toString();
    }

    private static JSONObject grant() throws Exception {
        return new JSONObject()
                .put("clientId", "client")
                .put("clientSecret", SECRET)
                .put("redirectUri", "org.pimalaya:/oauth2redirect")
                .put("state", STATE)
                .put("verifier", VERIFIER)
                .put("tokenEndpoint", "https://auth.example.org/token")
                .put("baseUrl", "imaps://imap.example.org")
                .put("email", "user@example.org")
                .put(PendingGrantStore.SAVED_AT, System.currentTimeMillis());
    }

    @Test
    public void aGrantRoundTripsSealed() throws Exception {
        JSONObject grant = grant();
        new PendingGrantStore(context).save(grant);

        assertEquals(Set.of("payload", "iv"), prefs().getAll().keySet());
        String raw = raw();
        assertFalse(raw.contains(STATE));
        assertFalse(raw.contains(VERIFIER));
        assertFalse(raw.contains(SECRET));
        assertFalse(raw.contains("user@example.org"));

        JSONObject loaded = new PendingGrantStore(context).load();
        assertEquals(grant.toString(), loaded.toString());
    }

    @Test
    public void aClearedGrantIsGone() throws Exception {
        PendingGrantStore store = new PendingGrantStore(context);
        store.save(grant());
        store.clear();

        assertTrue(prefs().getAll().isEmpty());
        assertNull(store.load());
    }

    @Test
    public void aPlaintextGrantFromAnOlderVersionIsWipedOnOpen() {
        prefs().edit()
                .putString("clientId", "client")
                .putString("clientSecret", SECRET)
                .putString("state", STATE)
                .putString("verifier", VERIFIER)
                .putString("email", "user@example.org")
                .commit();

        PendingGrantStore store = new PendingGrantStore(context);

        assertTrue(prefs().getAll().isEmpty());
        assertNull(store.load());
    }

    @Test
    public void aGrantThatNoLongerOpensIsDropped() throws Exception {
        new PendingGrantStore(context).save(grant());
        FakeAndroidKeyStore.KEYS.clear();

        assertNull(new PendingGrantStore(context).load());
        assertTrue(prefs().getAll().isEmpty());
    }

    /** A grant flow over an activity that is attached but never created. */
    private OauthFlow flow(MainActivity host) {
        OauthFlow flow = new OauthFlow(host, new AccountClient(), host);
        flow.onBusy = () -> {};
        flow.onAborted = () -> {};
        return flow;
    }

    private OauthFlow startedGoogleGrant(MainActivity host) {
        OauthFlow flow = flow(host);
        flow.startGoogleOauth("user@gmail.com", "https://mail.google.com/", "imaps://imap.gmail.com");
        return flow;
    }

    @Test
    public void aStartedGrantPersistsSealed() {
        MainActivity host = Robolectric.buildActivity(MainActivity.class).get();
        startedGoogleGrant(host);

        assertEquals(Set.of("payload", "iv"), prefs().getAll().keySet());
        JSONObject loaded = new PendingGrantStore(context).load();
        assertNotNull(loaded);
        assertFalse(raw().contains(loaded.optString("state")));
        assertFalse(raw().contains(loaded.optString("verifier")));
        assertEquals(Oauth.GOOGLE_TOKEN_ENDPOINT, loaded.optString("tokenEndpoint"));
    }

    @Test
    public void itsRedirectDropsTheGrantBeforeTheExchange() {
        MainActivity host = Robolectric.buildActivity(MainActivity.class).get();
        startedGoogleGrant(host);
        String state = new PendingGrantStore(context).load().optString("state");

        // NOTE: a fresh flow restores the grant from disk, as after the
        // process died in the browser; the redirect carries the grant's
        // state but an error, so the redeem fails off the main thread with
        // nothing sent, after the grant is gone.
        Uri redirect =
                Uri.parse(Oauth.GOOGLE_REDIRECT_URI + "?error=access_denied&state=" + state);
        flow(host).handleRedirect(new Intent(Intent.ACTION_VIEW, redirect));

        assertTrue(prefs().getAll().isEmpty());
        host.io.shutdownNow();
    }

    @Test
    public void aForgedOrForeignIntentLeavesTheGrantWaiting() throws Exception {
        MainActivity host = Robolectric.buildActivity(MainActivity.class).get();
        OauthFlow flow = startedGoogleGrant(host);
        String state = new PendingGrantStore(context).load().optString("state");

        for (String url :
                new String[] {
                    Oauth.GOOGLE_REDIRECT_URI + "?code=c0de&state=forged",
                    Oauth.GOOGLE_REDIRECT_URI + "?error=access_denied",
                    Oauth.GOOGLE_REDIRECT_URI + "x?code=c0de&state=" + state,
                    Oauth.REDIRECT_URI + "?code=c0de&state=" + state,
                    "https://example.org/?state=" + state,
                }) {
            flow.handleRedirect(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
            flow(host).handleRedirect(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        }

        assertEquals(state, new PendingGrantStore(context).load().optString("state"));
        host.io.shutdownNow();
    }

    @Test
    public void onlyTheRedirectUriWithItsStateIsTheRedirect() {
        String uri = Oauth.REDIRECT_URI;
        assertTrue(OauthFlow.isRedirectOf(uri + "?code=c&state=s", uri, "s"));
        assertFalse(OauthFlow.isRedirectOf(uri + "?code=c&state=t", uri, "s"));
        assertFalse(OauthFlow.isRedirectOf(uri + "?code=c", uri, "s"));
        assertFalse(OauthFlow.isRedirectOf(uri + "/x?code=c&state=s", uri, "s"));
        assertFalse(OauthFlow.isRedirectOf("org.pimalaya:/other?state=s", uri, "s"));
        assertFalse(OauthFlow.isRedirectOf(uri + "?state=s", null, "s"));
        assertFalse(OauthFlow.isRedirectOf(uri + "?state=", uri, ""));

        // NOTE: Entra answers pimalaya://oauth2redirect as .../oauth2redirect/.
        String microsoft = Oauth.MICROSOFT_REDIRECT_URI;
        assertTrue(OauthFlow.isRedirectOf(microsoft + "/?code=c&state=s", microsoft, "s"));
        assertTrue(OauthFlow.isRedirectOf(microsoft + "?code=c&state=s", microsoft, "s"));
        assertFalse(OauthFlow.isRedirectOf(microsoft + "/x?code=c&state=s", microsoft, "s"));
        assertFalse(OauthFlow.isRedirectOf(microsoft + "/?code=c&state=t", microsoft, "s"));
    }

    @Test
    public void anAbandonedGrantIsDroppedOnOpen() throws Exception {
        long old = System.currentTimeMillis() - OauthFlow.GRANT_TTL_MS - 1000;
        new PendingGrantStore(context).save(grant().put(PendingGrantStore.SAVED_AT, old));

        new PendingGrantStore(context);

        assertTrue(prefs().getAll().isEmpty());
    }

    @Test
    public void anAbandonedGrantRedeemsNothing() throws Exception {
        long old = System.currentTimeMillis() - OauthFlow.GRANT_TTL_MS - 1000;
        MainActivity host = Robolectric.buildActivity(MainActivity.class).get();
        OauthFlow flow = flow(host);
        new PendingGrantStore(context).save(grant().put(PendingGrantStore.SAVED_AT, old));

        flow.handleRedirect(
                new Intent(
                        Intent.ACTION_VIEW,
                        Uri.parse(Oauth.REDIRECT_URI + "?code=c0de&state=" + STATE)));

        assertTrue(prefs().getAll().isEmpty());
        host.io.shutdownNow();
    }

    @Test
    public void aSchemeGrantShowsTheLoader() {
        MainActivity host = Robolectric.buildActivity(MainActivity.class).get();
        OauthFlow flow = flow(host);
        boolean[] busy = {false};
        flow.onBusy = () -> busy[0] = true;

        flow.launchSchemeGrant(
                new OauthFlow.Target("user@example.org", "https://api.example.org/", null),
                new OauthFlow.AuthServer(
                        "https://auth.example.org/authorize",
                        "https://auth.example.org/token"),
                new OauthRequest("client", null, Oauth.REDIRECT_URI, "mail offline_access"),
                null);

        assertTrue(busy[0]);
        assertNotNull(new PendingGrantStore(context).load());
    }

    /**
     * The persisted grant as text, its random state and verifier and its
     * clock reading replaced by placeholders.
     */
    private String persisted() throws Exception {
        String plaintext =
                SecureStore.open(prefs().getString("payload", null), prefs().getString("iv", null));
        JSONObject grant = new JSONObject(plaintext);
        return plaintext
                .replace(grant.getString("state"), "STATE")
                .replace(grant.getString("verifier"), "VERIFIER")
                .replace(String.valueOf(grant.getLong(PendingGrantStore.SAVED_AT)), "SAVED");
    }

    @Test
    public void aStartedGrantPersistsWhatThePreviousReleaseDid() throws Exception {
        // NOTE: captured on the previous release, which an install may have
        // left mid-grant: the next one has to read it back.
        MainActivity host = Robolectric.buildActivity(MainActivity.class).get();
        OauthFlow flow = startedGoogleGrant(host);
        assertEquals(
                "{\"clientId\":\"991810147220-4f2s8id0pksdj5vtj1ivgpadnoibl4g1.apps.googleusercontent.com\",\"clientSecret\":\"\",\"redirectUri\":\"com.googleusercontent.apps.991810147220-4f2s8id0pksdj5vtj1ivgpadnoibl4g1:\\/oauth2redirect\",\"state\":\"STATE\",\"verifier\":\"VERIFIER\",\"tokenEndpoint\":\"https:\\/\\/oauth2.googleapis.com\\/token\",\"baseUrl\":\"imaps:\\/\\/imap.gmail.com\",\"email\":\"user@gmail.com\",\"issuer\":\"\",\"savedAt\":SAVED}",
                persisted());

        flow.launchSchemeGrant(
                new OauthFlow.Target(
                        "user@example.org",
                        "https://api.example.org/",
                        "https://api.example.org/jmap/session"),
                new OauthFlow.AuthServer(
                        "https://auth.example.org/authorize",
                        "https://auth.example.org/token"),
                new OauthRequest("client", "sec", Oauth.REDIRECT_URI, "mail offline_access"),
                "https://auth.example.org");
        assertEquals(
                "{\"clientId\":\"client\",\"clientSecret\":\"sec\",\"redirectUri\":\"org.pimalaya:\\/oauth2redirect\",\"state\":\"STATE\",\"verifier\":\"VERIFIER\",\"tokenEndpoint\":\"https:\\/\\/auth.example.org\\/token\",\"baseUrl\":\"https:\\/\\/api.example.org\\/\",\"email\":\"user@example.org\",\"issuer\":\"https:\\/\\/auth.example.org\",\"savedAt\":SAVED}",
                persisted());

        flow.launchSchemeGrant(
                new OauthFlow.Target("example.org", "https://api.example.org/", null),
                new OauthFlow.AuthServer(
                        "https://auth.example.org/authorize",
                        "https://auth.example.org/token"),
                new OauthRequest("client", null, Oauth.REDIRECT_URI, "mail"),
                null);
        assertEquals(
                "{\"clientId\":\"client\",\"clientSecret\":\"\",\"redirectUri\":\"org.pimalaya:\\/oauth2redirect\",\"state\":\"STATE\",\"verifier\":\"VERIFIER\",\"tokenEndpoint\":\"https:\\/\\/auth.example.org\\/token\",\"baseUrl\":\"https:\\/\\/api.example.org\\/\",\"email\":\"example.org\",\"issuer\":\"\",\"savedAt\":SAVED}",
                persisted());
        host.io.shutdownNow();
    }

    @Test
    public void aGrantThePreviousReleasePersistedStillRedeems() throws Exception {
        new PendingGrantStore(context).save(grant().put("issuer", ""));
        MainActivity host = Robolectric.buildActivity(MainActivity.class).get();

        // NOTE: restored from disk by a fresh flow: its state and redirect
        // URI are what tell the grant's own redirect from any other.
        flow(host).handleRedirect(
                new Intent(
                        Intent.ACTION_VIEW,
                        Uri.parse(Oauth.REDIRECT_URI + "?error=access_denied&state=forged")));
        assertEquals(STATE, new PendingGrantStore(context).load().optString("state"));

        flow(host).handleRedirect(
                new Intent(
                        Intent.ACTION_VIEW,
                        Uri.parse(Oauth.REDIRECT_URI + "?error=access_denied&state=" + STATE)));
        assertTrue(prefs().getAll().isEmpty());
        host.io.shutdownNow();
    }

    @Test
    public void aCancelledGrantIsGone() {
        MainActivity host = Robolectric.buildActivity(MainActivity.class).get();
        OauthFlow flow = startedGoogleGrant(host);

        flow.cancel();

        assertTrue(prefs().getAll().isEmpty());
        assertNull(new PendingGrantStore(context).load());
    }
}
