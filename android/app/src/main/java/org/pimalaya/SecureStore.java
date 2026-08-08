package org.pimalaya;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.pimalaya.client.Account;

/**
 * Caches the connected accounts locally, encrypted with an AES-GCM key held in
 * the Android Keystore. Passwords and OAuth tokens never touch disk in clear,
 * and no Jetpack Security / Tink dependency is pulled in (smallest APK). The
 * whole account list is one encrypted blob, keyed by the address; what each
 * account is connected for lives inside it, one block per domain.
 */
public class SecureStore {
    /**
     * Serializes every read-modify-write across the process: the blob
     * is rewritten whole, and instances are built ad-hoc (activity,
     * worker, sync service), so two concurrent token refreshes could
     * interleave last-writer-wins and persist a stale rotated refresh
     * token, permanently logging the account out.
     */
    private static final Object LOCK = new Object();

    private static final String PREFS = "pimalaya.account";
    private static final String KEY_PAYLOAD = "payload";
    private static final String KEY_IV = "iv";
    private static final String ANDROID_KEYSTORE = "AndroidKeyStore";
    private static final String KEY_ALIAS = "pimalaya.account.key";
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int GCM_TAG_BITS = 128;

    private final SharedPreferences prefs;

    public SecureStore(Context context) {
        prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** Every cached account, empty on first launch. */
    public List<AccountEntry> loadAll() {
        synchronized (LOCK) {
            String payload = prefs.getString(KEY_PAYLOAD, null);
            String iv = prefs.getString(KEY_IV, null);
            if (payload == null || iv == null) {
                return new ArrayList<>();
            }

            try {
                Cipher cipher = Cipher.getInstance(TRANSFORMATION);
                GCMParameterSpec spec =
                        new GCMParameterSpec(GCM_TAG_BITS, Base64.decode(iv, Base64.NO_WRAP));
                cipher.init(Cipher.DECRYPT_MODE, secretKey(), spec);

                byte[] plaintext = cipher.doFinal(Base64.decode(payload, Base64.NO_WRAP));
                return decode(new String(plaintext, StandardCharsets.UTF_8));
            } catch (Exception error) {
                throw new IllegalStateException("Could not load the accounts", error);
            }
        }
    }

    /** Adds or replaces an account, keyed by its address, then persists. */
    public void add(AccountEntry entry) {
        synchronized (LOCK) {
            List<AccountEntry> entries = loadAll();
            entries.removeIf(candidate -> candidate.email.equals(entry.email));
            entries.add(entry);
            save(entries);
        }
    }

    /**
     * Connects one domain of an address, keeping every domain it already
     * covers, then persists.
     *
     * <p>This is what makes "set up calendar for this account" a step rather
     * than a second account: the entry is merged into the one that is already
     * there, and only appears as a new account when the address is new.
     */
    public AccountEntry connect(String email, PimDomain domain, AccountConnection connection) {
        synchronized (LOCK) {
            List<AccountEntry> entries = loadAll();
            AccountEntry existing = null;
            for (AccountEntry entry : entries) {
                if (entry.email.equals(email)) {
                    existing = entry;
                }
            }

            AccountEntry merged =
                    existing == null
                            ? AccountEntry.of(email, domain, connection)
                            : existing.with(domain, connection);
            entries.removeIf(entry -> entry.email.equals(email));
            entries.add(merged);
            save(entries);
            return merged;
        }
    }

    /** Removes an address and every domain it covered, then persists. */
    public void remove(String email) {
        synchronized (LOCK) {
            List<AccountEntry> entries = loadAll();
            entries.removeIf(entry -> entry.email.equals(email));
            save(entries);
        }
    }

    private void save(List<AccountEntry> entries) {
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, secretKey());

            byte[] ciphertext = cipher.doFinal(encode(entries).getBytes(StandardCharsets.UTF_8));

            prefs.edit()
                    .putString(KEY_PAYLOAD, Base64.encodeToString(ciphertext, Base64.NO_WRAP))
                    .putString(KEY_IV, Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP))
                    .apply();
        } catch (Exception error) {
            throw new IllegalStateException("Could not save the accounts", error);
        }
    }

    /** Returns the Keystore AES key, generating it on first use. */
    private SecretKey secretKey() throws Exception {
        KeyStore keystore = KeyStore.getInstance(ANDROID_KEYSTORE);
        keystore.load(null);

        KeyStore.Entry entry = keystore.getEntry(KEY_ALIAS, null);
        if (entry instanceof KeyStore.SecretKeyEntry) {
            return ((KeyStore.SecretKeyEntry) entry).getSecretKey();
        }

        KeyGenerator generator =
                KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE);
        generator.init(
                new KeyGenParameterSpec.Builder(
                                KEY_ALIAS,
                                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                        .build());
        return generator.generateKey();
    }

    private static String encode(List<AccountEntry> entries) {
        try {
            JSONArray array = new JSONArray();
            for (AccountEntry entry : entries) {
                JSONObject connections = new JSONObject();
                for (PimDomain domain : entry.domains()) {
                    connections.put(domain.id, encode(entry.connection(domain)));
                }
                array.put(
                        new JSONObject()
                                .put("email", entry.email)
                                .put("connections", connections));
            }
            return array.toString();
        } catch (JSONException error) {
            throw new IllegalStateException("Could not encode the accounts", error);
        }
    }

    private static JSONObject encode(AccountConnection connection) throws JSONException {
        JSONObject object =
                new JSONObject()
                        .put("baseUrl", connection.account.baseUrl)
                        .put("login", connection.account.login)
                        .put("password", connection.account.password);
        if (connection.refreshToken != null) {
            object.put("refreshToken", connection.refreshToken)
                    .put("tokenEndpoint", connection.tokenEndpoint)
                    .put("clientId", connection.clientId);
            if (connection.clientSecret != null) {
                object.put("clientSecret", connection.clientSecret);
            }
        }
        return object;
    }

    private static List<AccountEntry> decode(String json) {
        try {
            JSONArray array = new JSONArray(json);
            List<AccountEntry> entries = new ArrayList<>(array.length());
            for (int index = 0; index < array.length(); index++) {
                JSONObject object = array.getJSONObject(index);
                String email = object.getString("email");

                JSONObject connections = object.optJSONObject("connections");
                if (connections == null) {
                    // NOTE: an account stored before an account could cover
                    // several domains. Its one connection is a contacts one,
                    // because contacts is all the app could connect then.
                    entries.add(
                            AccountEntry.of(email, PimDomain.CONTACTS, decodeConnection(object)));
                    continue;
                }

                Map<PimDomain, AccountConnection> decoded = new EnumMap<>(PimDomain.class);
                for (java.util.Iterator<String> ids = connections.keys(); ids.hasNext(); ) {
                    String id = ids.next();
                    decoded.put(
                            PimDomain.byId(id), decodeConnection(connections.getJSONObject(id)));
                }
                entries.add(new AccountEntry(email, decoded));
            }
            return entries;
        } catch (JSONException error) {
            throw new IllegalStateException("Could not decode the accounts", error);
        }
    }

    private static AccountConnection decodeConnection(JSONObject object) throws JSONException {
        Account account =
                new Account(
                        object.getString("baseUrl"),
                        object.getString("login"),
                        object.getString("password"));
        return new AccountConnection(
                account,
                object.isNull("refreshToken") ? null : object.optString("refreshToken"),
                object.isNull("tokenEndpoint") ? null : object.optString("tokenEndpoint"),
                object.isNull("clientId") ? null : object.optString("clientId"),
                object.isNull("clientSecret") ? null : object.optString("clientSecret"));
    }
}
