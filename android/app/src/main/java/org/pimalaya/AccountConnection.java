package org.pimalaya;

/**
 * One domain's half of an account: where that domain lives, and which
 * of the account's credentials signs in to it.
 *
 * <p>The endpoint is per domain and the credential is not, which is why
 * this holds a name rather than the secret itself. A provider is under
 * no obligation to serve mail, contacts and calendars from one endpoint,
 * and plenty do not; but the sign-in that reached them is frequently one
 * grant, and duplicating it per domain is what let the copies drift
 * apart ({@link AccountCredential}).
 *
 * <p>A JMAP provider is the case that settles it: RFC 8620 has one
 * session resource behind one authentication, and the capabilities each
 * account advertises say which domains it serves. Three connections
 * there are three names for one endpoint and one credential, which is
 * exactly what one account with several domains is meant to express.
 */
final class AccountConnection {
    /** Where the domain lives. */
    final String baseUrl;

    /** Which of the account's credentials signs in to it. */
    final String credentialId;

    AccountConnection(String baseUrl, String credentialId) {
        this.baseUrl = baseUrl;
        this.credentialId = credentialId;
    }
}
