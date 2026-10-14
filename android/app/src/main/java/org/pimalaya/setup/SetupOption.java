package org.pimalaya.setup;

import org.pimalaya.client.AuthMethod;

/** One offered way to connect one domain: a server and a way in. */
final class SetupOption {
    /**
     * The discovered service this connects, or null when nothing discovered it.
     *
     * <p>Read by the submission section: a JMAP option offers none, RFC 8621
     * submitting through the session it reads from.
     */
    final String service;

    final String label;
    final String detail;

    /** What the account stores as its server for this domain. */
    final String baseUrl;

    /**
     * The RFC 8707 resource an OAuth grant asks for, or null.
     *
     * <p>Not {@link #baseUrl}: a JMAP base is the internal {@code jmap://}
     * marker, which Fastmail answers with {@code invalid_target}. The resource
     * is the HTTPS session URL the service was discovered at.
     */
    final String resource;

    final AuthMethod method;
    final String login;

    SetupOption(
            String service,
            String label,
            String detail,
            String baseUrl,
            String resource,
            AuthMethod method,
            String login) {
        this.service = service;
        this.label = label;
        this.detail = detail;
        this.baseUrl = baseUrl;
        this.resource = resource;
        this.method = method;
        this.login = login;
    }

    /**
     * Whether reading mail through this option asks where to send.
     *
     * <p>IMAP, Graph and Gmail do; manual entry is an IMAP server.
     */
    boolean asksSubmission() {
        return "imap".equals(service) || readsOverApi() || isManual();
    }

    /** Whether this option reads mail over a provider API, which also sends. */
    boolean readsOverApi() {
        return "msgraph".equals(service) || "gmail".equals(service);
    }

    /** Whether this option asks for a server instead of proposing one. */
    boolean isManual() {
        return service == null && method == null;
    }

    /** Whether this option signs in through a browser grant. */
    boolean isOauth() {
        return method != null
                && method.type() != AuthMethod.Type.PASSWORD
                && method.type() != AuthMethod.Type.BEARER;
    }
}
