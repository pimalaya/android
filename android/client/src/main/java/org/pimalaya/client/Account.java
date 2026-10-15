package org.pimalaya.client;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * A usable account endpoint: where one domain lives, plus the credentials.
 *
 * <p>An empty login means the secret is an OAuth 2.0 access token, sent as
 * Bearer instead of Basic. {@link #submitUrl} is mail's submission endpoint,
 * null for other domains and for backends submitting where they read (JMAP).
 */
public final class Account {
    /** The account base URL, opaque, naming the backend. */
    public final String baseUrl;
    /** The login; empty when {@link #secret} is a Bearer token. */
    public final String login;
    /** The password, or the OAuth 2.0 access token. */
    public final String secret;

    /** Where mail is submitted, or null when there is nowhere separate. */
    public final String submitUrl;

    /**
     * The account's own address, or null for none.
     *
     * <p>Google and JMAP announce a calendar write to attendees only for a
     * meeting it organizes.
     */
    public final String address;

    /** What the base URL tells, null until first asked ({@link #info}). */
    private volatile BackendInfo info;

    /** An endpoint with no separate submission endpoint. */
    public Account(String baseUrl, String login, String secret) {
        this(baseUrl, login, secret, null);
    }

    /** An endpoint with its submission endpoint, null for none. */
    public Account(String baseUrl, String login, String secret, String submitUrl) {
        this(baseUrl, login, secret, submitUrl, null);
    }

    /** An endpoint with its submission endpoint and the account's address. */
    public Account(
            String baseUrl, String login, String secret, String submitUrl, String address) {
        this.baseUrl = baseUrl;
        this.login = login;
        this.secret = secret;
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
            account.put("secret", secret);
            account.put("submitUrl", submitUrl);
            return account;
        } catch (JSONException error) {
            throw new PimalayaException("Unwritable account");
        }
    }

    /**
     * What the base URL tells of the backend, read once per account.
     *
     * <p>The facts a caller decides by (rs/account.rs {@code AccountInfo}).
     */
    public BackendInfo info() {
        BackendInfo read = info;
        if (read == null) {
            // NOTE: racing readers each read the same pure answer.
            String url = baseUrl == null ? "" : baseUrl;
            read = BackendInfo.from(Replies.object(Native.accountInfo(url)));
            info = read;
        }
        return read;
    }
}
