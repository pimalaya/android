package org.pimalaya.client;

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

    /** An endpoint with no separate submission endpoint. */
    public Account(String baseUrl, String login, String password) {
        this(baseUrl, login, password, null);
    }

    /** An endpoint with its submission endpoint, null for none. */
    public Account(String baseUrl, String login, String password, String submitUrl) {
        this.baseUrl = baseUrl;
        this.login = login;
        this.password = password;
        this.submitUrl = submitUrl;
    }
}
