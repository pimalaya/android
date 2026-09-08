package org.pimalaya.client;

/**
 * A usable account endpoint: where one domain lives plus the entered
 * credentials. An empty login means the password field carries an
 * OAuth 2.0 access token, authenticated as Bearer instead of Basic.
 *
 * <p>Mail is the one domain that reads and writes in two places, so it
 * carries a second endpoint: {@link #submitUrl}, where a message is
 * handed over. Every other domain leaves it null, and so does a mail
 * account whose backend submits through the endpoint it reads from
 * (JMAP, whose session resource serves both).
 */
public final class Account {
    public final String baseUrl;
    public final String login;
    public final String password;

    /** Where mail is submitted, or null when there is nowhere separate. */
    public final String submitUrl;

    public Account(String baseUrl, String login, String password) {
        this(baseUrl, login, password, null);
    }

    public Account(String baseUrl, String login, String password, String submitUrl) {
        this.baseUrl = baseUrl;
        this.login = login;
        this.password = password;
        this.submitUrl = submitUrl;
    }
}
