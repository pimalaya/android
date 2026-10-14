package org.pimalaya.setup;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;
import org.json.JSONObject;
import org.pimalaya.account.SecureStore;

/**
 * Keeps the in-flight OAuth grant across the browser hop, which routinely
 * outlives the process: its client, redirect, CSRF state, PKCE verifier and
 * where the tokens go. The grant is sealed under the account key, the same
 * AES-GCM scheme as {@link SecureStore}, and never sits on disk in clear. A
 * grant older than {@link OauthFlow#GRANT_TTL_MS} is abandoned and dropped.
 */
final class PendingGrantStore {
    /** The grant's field holding when it started, in wall-clock milliseconds. */
    static final String SAVED_AT = "savedAt";

    private static final String PREFS = "pimalaya.oauth";
    private static final String KEY_PAYLOAD = "payload";
    private static final String KEY_IV = "iv";

    private final SharedPreferences prefs;

    /**
     * Opens the store, wiping a grant an older version left in clear and
     * one abandoned in the browser.
     */
    PendingGrantStore(Context context) {
        prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);

        // NOTE: older versions wrote one plaintext key per field (state,
        // verifier, clientSecret...); any key but the sealed pair is one.
        for (String key : prefs.getAll().keySet()) {
            if (!key.equals(KEY_PAYLOAD) && !key.equals(KEY_IV)) {
                clear();
                break;
            }
        }
        load();
    }

    /** Seals and persists the grant, replacing any previous one. */
    void save(JSONObject grant) throws Exception {
        String[] sealed = SecureStore.seal(grant.toString());
        prefs.edit()
                .clear()
                .putString(KEY_PAYLOAD, sealed[0])
                .putString(KEY_IV, sealed[1])
                .apply();
    }

    /**
     * The persisted grant, or null when there is none, it no longer opens (a
     * lost key, a tampered blob) or it was abandoned, in which case it is
     * dropped.
     */
    JSONObject load() {
        String payload = prefs.getString(KEY_PAYLOAD, null);
        String iv = prefs.getString(KEY_IV, null);
        if (payload == null || iv == null) {
            return null;
        }

        JSONObject grant;
        try {
            grant = new JSONObject(SecureStore.open(payload, iv));
        } catch (Exception error) {
            Log.w("pimalaya", "dropping an unreadable oauth grant");
            clear();
            return null;
        }
        if (OauthFlow.expired(grant.optLong(SAVED_AT), System.currentTimeMillis())) {
            Log.i("pimalaya", "dropping an abandoned oauth grant");
            clear();
            return null;
        }
        return grant;
    }

    /** Drops the persisted grant, if any. */
    void clear() {
        prefs.edit().clear().apply();
    }
}
