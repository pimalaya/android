package org.pimalaya.client;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * A usable account endpoint: where one domain lives, plus the credentials.
 *
 * <p>An empty login means the password is an OAuth 2.0 access token, sent as
 * Bearer instead of Basic. {@link #submitUrl} is mail's submission endpoint,
 * null for other domains and for backends submitting where they read (JMAP).
 */
public final class Account {
    /** The account base URL, opaque, naming the backend. */
    public final String baseUrl;
    /** The login; empty when {@link #password} is a Bearer token. */
    public final String login;
    /** The password, or the OAuth 2.0 access token. */
    public final String password;

    /** Where mail is submitted, or null when there is nowhere separate. */
    public final String submitUrl;

    /**
     * The account's own address, or null for none.
     *
     * <p>Google and JMAP announce a calendar write to attendees only for a
     * meeting it organizes.
     */
    public final String address;

    /** An endpoint with no separate submission endpoint. */
    public Account(String baseUrl, String login, String password) {
        this(baseUrl, login, password, null);
    }

    /** An endpoint with its submission endpoint, null for none. */
    public Account(String baseUrl, String login, String password, String submitUrl) {
        this(baseUrl, login, password, submitUrl, null);
    }

    /** An endpoint with its submission endpoint and the account's address. */
    public Account(
            String baseUrl, String login, String password, String submitUrl, String address) {
        this.baseUrl = baseUrl;
        this.login = login;
        this.password = password;
        this.submitUrl = submitUrl;
        this.address = address;
    }

    /**
     * The account as the bridge's network verbs take it (rs/account.rs
     * {@code AccountJson}).
     *
     * <p>{@code {address?, baseUrl, login, secret, submitUrl?}}; a null
     * component is an absent key, which the bridge reads empty.
     */
    JSONObject toJson() {
        try {
            JSONObject account = new JSONObject();
            account.put("address", address);
            account.put("baseUrl", baseUrl);
            account.put("login", login);
            account.put("secret", password);
            account.put("submitUrl", submitUrl);
            return account;
        } catch (JSONException error) {
            throw new PimalayaException("Unwritable account");
        }
    }
}
