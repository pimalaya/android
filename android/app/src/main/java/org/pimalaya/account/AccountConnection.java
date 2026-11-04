package org.pimalaya.account;

/**
 * One domain's half of an account: its endpoint, and which credential signs in.
 *
 * <p>It names the credential, since one grant often covers several domains
 * ({@link AccountCredential}), as a JMAP session does (RFC 8620). Only mail has
 * a second endpoint (IMAP reads, SMTP submits); others leave it null.
 */
public final class AccountConnection {
    /** Where the domain lives. */
    public final String baseUrl;

    /** Which of the account's credentials signs in to it. */
    final String credentialId;

    /** Where mail is submitted, or null when there is nowhere separate. */
    public final String submitUrl;

    /**
     * Which credential submission signs in with, or null for the one reading
     * does: SMTP is a second server, which may take another login.
     */
    final String submitCredentialId;

    AccountConnection(String baseUrl, String credentialId) {
        this(baseUrl, credentialId, null);
    }

    AccountConnection(String baseUrl, String credentialId, String submitUrl) {
        this(baseUrl, credentialId, submitUrl, null);
    }

    AccountConnection(
            String baseUrl, String credentialId, String submitUrl, String submitCredentialId) {
        this.baseUrl = baseUrl;
        this.credentialId = credentialId;
        this.submitUrl = submitUrl;
        this.submitCredentialId = submitCredentialId;
    }
}
