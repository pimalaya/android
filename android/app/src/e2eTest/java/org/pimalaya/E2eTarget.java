package org.pimalaya;

import org.pimalaya.client.PimalayaClient;
import org.pimalaya.client.ServiceConfig;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;

/**
 * One provider account reached over one protocol for one domain: where it
 * is, how its credential is obtained, which fixture prepares its server, and
 * what it supports. A new target (a Stalwart container, Microsoft Graph once
 * a user token can be supplied) is one more entry in {@link #of}.
 */
final class E2eTarget {
    /** The mail targets, in report order. */
    static final List<String> MAIL =
            List.of("google-gmail", "google-imap", "microsoft-imap", "fastmail-jmap", "fastmail-imap");

    /** The contacts targets. */
    static final List<String> CONTACTS =
            List.of("google-carddav", "fastmail-carddav", "fastmail-jmap-contacts");

    /** The calendar targets. */
    static final List<String> CALENDAR =
            List.of("google-gcal", "google-caldav", "fastmail-caldav", "fastmail-jmap-calendar");

    /** Every target the suite knows, in report order. */
    static final List<String> ALL = all();

    private static final String GMAIL_SCOPE = "https://mail.google.com/";

    private static final String CARDDAV_SCOPE =
            "https://www.googleapis.com/auth/carddav https://www.googleapis.com/auth/contacts";

    private static final String CALENDAR_SCOPE = "https://www.googleapis.com/auth/calendar";

    private static final String OUTLOOK_SCOPE = "https://outlook.office365.com/.default";

    private static final String FASTMAIL_SESSION = "https://api.fastmail.com/jmap/session";

    /** What discovery found per address, once a run. */
    private static final Map<String, List<ServiceConfig>> DISCOVERED = new HashMap<>();

    String id;

    /** The provider, which `-Pe2e.targets` may name for all of its targets. */
    String provider;

    PimDomain domain;

    String address;

    /**
     * The discovered service the account reads over: `gmail`, `imap`,
     * `jmap`, `carddav`, `caldav` or `gcal`.
     */
    String service;

    /** Whether mail is submitted over SMTP, not over the reading session. */
    boolean smtp;

    /** Whether the fixture can seed and read a keyword the app does not model. */
    boolean keywords;

    /** Why the target cannot run here, null when it can. */
    String missing;

    /** The login presented with the secret; empty for a bearer token. */
    String login = "";

    Callable<String> secret;

    /** A mail target's fixture, null for the other domains. */
    Callable<E2eFixture> mailFixture;

    /** A contacts or calendar target's fixture, null for mail. */
    Callable<E2eItemFixture> itemFixture;

    private E2eTarget() {}

    static E2eTarget of(String id) {
        String google = E2eSecrets.env("GOOGLE_SERVICE_ACCOUNT_SUBJECT", "google@pimalaya.org");
        String microsoft = E2eSecrets.env("MICROSOFT_USER", "microsoft@pimalaya.onmicrosoft.com");
        String fastmail = E2eSecrets.env("FASTMAIL_USER", "pimalaya@fastmail.org");

        E2eTarget target = new E2eTarget();
        target.id = id;
        target.provider = id.substring(0, id.indexOf('-'));
        switch (target.provider) {
            case "google":
                target.address = google;
                target.missing = E2eSecrets.googleMissing();
                break;
            case "microsoft":
                target.address = microsoft;
                target.missing = E2eSecrets.microsoftMissing();
                break;
            default:
                target.address = fastmail;
                break;
        }

        switch (id) {
            case "google-gmail":
            case "google-imap":
                {
                    Callable<String> token = () -> E2eSecrets.googleToken(google, GMAIL_SCOPE);
                    target.domain = PimDomain.MAIL;
                    target.service = id.equals("google-gmail") ? "gmail" : "imap";
                    target.smtp = id.equals("google-imap");
                    target.secret = token;
                    target.mailFixture = () -> new E2eGmailFixture(token);
                    return target;
                }
            case "microsoft-imap":
                {
                    Callable<String> token = () -> E2eSecrets.microsoftToken(OUTLOOK_SCOPE);
                    target.domain = PimDomain.MAIL;
                    target.service = "imap";
                    target.smtp = true;
                    target.secret = token;
                    target.mailFixture =
                            () ->
                                    new E2eImapFixture(
                                            "outlook.office365.com", microsoft, null, token.call());
                    return target;
                }
            case "fastmail-jmap":
                target.domain = PimDomain.MAIL;
                target.service = "jmap";
                target.keywords = true;
                target.missing = E2eSecrets.missing("FASTMAIL_JMAP_TOKEN");
                target.secret = () -> E2eSecrets.env("FASTMAIL_JMAP_TOKEN");
                target.mailFixture =
                        () ->
                                new E2eJmapFixture(
                                        FASTMAIL_SESSION, E2eSecrets.env("FASTMAIL_JMAP_TOKEN"));
                return target;
            case "fastmail-imap":
                target.domain = PimDomain.MAIL;
                target.service = "imap";
                target.smtp = true;
                target.keywords = true;
                target.missing = E2eSecrets.missing("FASTMAIL_APP_PASSWORD");
                target.login = fastmail;
                target.secret = () -> E2eSecrets.env("FASTMAIL_APP_PASSWORD");
                target.mailFixture =
                        () ->
                                new E2eImapFixture(
                                        "imap.fastmail.com",
                                        fastmail,
                                        E2eSecrets.env("FASTMAIL_APP_PASSWORD"),
                                        null);
                return target;
            case "google-carddav":
                {
                    Callable<String> token = () -> E2eSecrets.googleToken(google, CARDDAV_SCOPE);
                    target.domain = PimDomain.CONTACTS;
                    target.service = "carddav";
                    target.secret = token;
                    target.itemFixture = () -> new E2eGoogleContactsFixture(token);
                    return target;
                }
            case "google-gcal":
            case "google-caldav":
                {
                    Callable<String> token = () -> E2eSecrets.googleToken(google, CALENDAR_SCOPE);
                    target.domain = PimDomain.CALENDAR;
                    target.service = id.equals("google-gcal") ? "gcal" : "caldav";
                    target.secret = token;
                    target.itemFixture = () -> new E2eGoogleCalendarFixture(token);
                    return target;
                }
            case "fastmail-carddav":
            case "fastmail-caldav":
                {
                    boolean calendar = id.equals("fastmail-caldav");
                    String home =
                            calendar
                                    ? "https://caldav.fastmail.com/dav/calendars/user/"
                                    : "https://carddav.fastmail.com/dav/addressbooks/user/";
                    target.domain = calendar ? PimDomain.CALENDAR : PimDomain.CONTACTS;
                    target.service = id.substring(9);
                    target.missing = E2eSecrets.missing("FASTMAIL_DAV_PASSWORD");
                    target.login = fastmail;
                    target.secret = () -> E2eSecrets.env("FASTMAIL_DAV_PASSWORD");
                    target.itemFixture =
                            () ->
                                    new E2eDavFixture(
                                            home + fastmail + "/",
                                            calendar,
                                            fastmail,
                                            E2eSecrets.env("FASTMAIL_DAV_PASSWORD"));
                    return target;
                }
            case "fastmail-jmap-contacts":
            case "fastmail-jmap-calendar":
                // NOTE: no fixture: Fastmail's JMAP session grants the test
                // token mail and submission alone, no contacts or calendars.
                target.domain = id.contains("contacts") ? PimDomain.CONTACTS : PimDomain.CALENDAR;
                target.service = "jmap";
                target.missing = "a fixture for " + id + ", the JMAP session offering no such capability";
                return target;
            default:
                throw new IllegalArgumentException("unknown e2e target " + id);
        }
    }

    /**
     * The targets `-Pe2e.targets` asks for: providers (`google`), single
     * targets (`google-imap`) or `all`, comma separated.
     */
    static Set<String> asked() {
        String property = System.getProperty("e2e.targets", "");
        Set<String> asked = new LinkedHashSet<>();
        for (String name : property.split(",")) {
            String trimmed = name.trim();
            for (String id : ALL) {
                if (trimmed.equals("all") || id.equals(trimmed) || id.startsWith(trimmed + "-")) {
                    asked.add(id);
                }
            }
        }
        return asked;
    }

    /** Why this target does not run, null when it does. */
    String skipped() {
        if (!asked().contains(id)) {
            return "not in e2e.targets";
        }
        if (missing != null && missing.startsWith("a fixture")) {
            return "not supported yet: " + missing;
        }
        return missing == null ? null : "no credential: " + missing + " is unset";
    }

    /**
     * The account as the connection flow builds it: discovery for the address,
     * the domain's endpoint (and mail's submission) derived by the flow's own
     * rules, the user put in a mail URL for a token, a fresh credential.
     */
    AccountEntry account(PimalayaClient client) throws Exception {
        List<ServiceConfig> configs;
        synchronized (DISCOVERED) {
            configs = DISCOVERED.get(address);
            if (configs == null) {
                configs = client.searchAll(address, null);
                DISCOVERED.put(address, configs);
            }
        }

        String base = null;
        List<String> found = new ArrayList<>();
        for (ServiceConfig config : configs) {
            found.add(config.service);
            if (base == null && service.equals(config.service)) {
                base = OnboardingFlow.endpointUrl(config, address);
            }
        }
        if (base == null) {
            throw new AssertionError("discovery found no " + service + " for " + address + ": " + found);
        }

        String submit = null;
        if (smtp) {
            List<String> submits = new ArrayList<>();
            for (ServiceConfig config : configs) {
                String url = OnboardingFlow.submissionUrl(config);
                if (url != null) {
                    submits.add(url);
                }
            }
            // NOTE: implicit TLS first, as the flow offers it.
            submits.sort(
                    (left, right) ->
                            Boolean.compare(!left.startsWith("smtps:"), !right.startsWith("smtps:")));
            if (submits.isEmpty()) {
                throw new AssertionError("discovery found no submission for " + address);
            }
            submit = submits.get(0);
        }

        if (domain == PimDomain.MAIL && login.isEmpty()) {
            base = OnboardingFlow.asUser(base, address);
            submit = submit == null ? null : OnboardingFlow.asUser(submit, address);
        }
        return AccountEntry.empty(address)
                .with(domain, base, submit, AccountCredential.password(login, secret.call()));
    }

    /** The same account submitting nowhere reachable: a phone with no network to send on. */
    static AccountEntry unreachable(AccountEntry account) {
        AccountConnection mail = account.connection(PimDomain.MAIL);
        return account.with(
                PimDomain.MAIL, mail.baseUrl, "smtps://127.0.0.1:1", account.credential(PimDomain.MAIL));
    }

    @Override
    public String toString() {
        return id;
    }

    /** One domain's targets, for a parameterized runner. */
    static List<Object[]> parameters(List<String> ids) {
        List<Object[]> parameters = new ArrayList<>();
        for (String id : ids) {
            parameters.add(new Object[] {id});
        }
        return parameters;
    }

    private static List<String> all() {
        List<String> all = new ArrayList<>(MAIL);
        all.addAll(CONTACTS);
        all.addAll(CALENDAR);
        return all;
    }
}
