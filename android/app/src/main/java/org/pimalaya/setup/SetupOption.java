package org.pimalaya.setup;

import org.pimalaya.client.AuthMethod;

/** One offered way to connect one domain: a server and a way in. */
final class SetupOption {
    /**
     * The discovered service this connects, or null when nothing
     * discovered it: a provider sign-in, or manual entry.
     *
     * <p>Kept beside the label, which is the same thing in the
     * reader's words. What reads it is the submission section, which
     * a JMAP option has nothing to offer: RFC 8621 submits through
     * the session it reads from, so an SMTP server under it would be
     * a second account nobody asked for.
     */
    final String service;

    final String label;
    final String detail;

    /** What the account stores as its server for this domain. */
    final String baseUrl;

    /**
     * The RFC 8707 resource an OAuth grant is asked for, or null when the
     * service has no URI form.
     *
     * <p>Deliberately not {@link #baseUrl}. A JMAP account's base is the
     * internal {@code jmap://} marker the backend dispatches on, and an
     * authorization server asked to issue a token for that URI has no idea
     * what it names: Fastmail answers {@code invalid_target}. The resource
     * is the HTTPS session URL the service was discovered at, which is what
     * the server actually protects.
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
     * Whether reading mail through this option asks where to send: IMAP,
     * Graph and Gmail do, manual entry being an IMAP server typed by
     * hand.
     */
    boolean asksSubmission() {
        return "imap".equals(service) || readsOverApi() || isManual();
    }

    /**
     * Whether this option reads mail over a provider API, which sends
     * through the same API and nothing else.
     */
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
                && method.type != AuthMethod.Type.PASSWORD
                && method.type != AuthMethod.Type.BEARER;
    }
}
