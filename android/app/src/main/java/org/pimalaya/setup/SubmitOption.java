package org.pimalaya.setup;

import org.pimalaya.client.AuthMethod;

/**
 * One offered way to send: a server, or nowhere.
 *
 * <p>Its own type rather than another {@link SetupOption}, because it
 * is not a way to connect a domain. Nobody picks between reading mail
 * and sending it: the two ride together, the reading one decides how
 * the account signs in, and this one only says where the messages go.
 */
final class SubmitOption {
    final String label;

    /** The host a row names, null for the row that connects nothing. */
    final String detail;

    /**
     * Where mail is submitted, null until manual entry answers and
     * for the row that says not to send at all.
     */
    String url;

    /** Whether picking it asks for a server rather than proposing one. */
    final boolean manual;

    /**
     * What the mail grant asks for on its behalf, null when nothing: it
     * signs in with the mail connection's credential, so a browser
     * grant for mail has to cover sending too.
     */
    final String scope;

    /**
     * The provider API it submits through (`msgraph`, `gmail`), null for
     * SMTP and the rows around it. Only an account reading over that API
     * can: its token is the API's, where SMTP takes the mail server's.
     */
    final String through;

    /**
     * How an SMTP row signs in, null for the rows that name no method.
     * It signs in with the mail connection's credential, so only a row
     * of the same kind as the mail sign-in can use it.
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
