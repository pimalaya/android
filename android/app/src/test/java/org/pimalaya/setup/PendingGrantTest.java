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
        OauthFlow flow = new OauthFlow(host, host.clients.account(), host);
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
                "user@example.org",
                "https://api.example.org/",
                "client",
                null,
                "https://auth.example.org/authorize",
                "https://auth.example.org/token",
                "mail offline_access",
                null,
                null);

        assertTrue(busy[0]);
        assertNotNull(new PendingGrantStore(context).load());
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
