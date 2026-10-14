package org.pimalaya.setup;

import org.pimalaya.client.AuthMethod;

/**
 * One offered way to send: a server, or nowhere.
 *
 * <p>Not a {@link SetupOption}: reading and sending ride together, the reading
 * option decides how the account signs in, and this one only says where
 * messages go.
 */
final class SubmitOption {
    final String label;

    /** The host a row names, null for the row that connects nothing. */
    final String detail;

    /**
     * Where mail is submitted, null until manual entry answers or not sending.
     */
    String url;

    /** Whether picking it asks for a server rather than proposing one. */
    final boolean manual;

    /**
     * What the mail grant asks for on its behalf, null when nothing.
     *
     * <p>It signs in with the mail connection's credential, so a browser grant
     * for mail has to cover sending too.
     */
    final String scope;

    /**
     * The provider API it submits through (`msgraph`, `gmail`), null for SMTP.
     *
     * <p>Only an account reading over that API can: its token is the API's.
     */
    final String through;

    /**
     * How an SMTP row signs in, null for rows naming no method.
     *
     * <p>It uses the mail connection's credential, so only a row of the mail
     * sign-in's kind can.
     */
    final AuthMethod.Type auth;

    SubmitOption(String label, String detail, String url, boolean manual) {
        this(label, detail, url, manual, null, null, null);
    }

    SubmitOption(
            String label,
            String detail,
            String url,
            boolean manual,
            String scope,
            String through,
            AuthMethod.Type auth) {
        this.label = label;
        this.detail = detail;
        this.url = url;
        this.manual = manual;
        this.scope = scope;
        this.through = through;
        this.auth = auth;
    }

    /** Whether it is the row that sends nothing. */
    boolean none() {
        return url == null && !manual && through == null;
    }
}
