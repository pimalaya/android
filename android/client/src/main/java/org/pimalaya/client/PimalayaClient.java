package org.pimalaya.client;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * The only surface the app needs: config search (endpoints plus
 * authentication methods) and RFC 6764 discovery, OAuth 2.0 token
 * refresh (the code-exchange side lives on {@link OauthSession}), the
 * onboarding connection check, and the addressbook/card operations.
 * Owns the TLS sockets and the Rust bridge; the app sees neither.
 * Every call blocks, so callers must run it off the main thread.
 *
 * <p>Four backends hide behind the same operations: CardDAV, Microsoft
 * Graph, JMAP for Contacts (RFC 9610) and the Google People API. The
 * Rust bridge tells them apart by the account's base URL (the
 * non-CardDAV backends carry a sentinel scheme there) and dispatches
 * every operation; the base URL is opaque here, built by the
 * {@code *Base} helpers and threaded back verbatim. Graph and Google
 * accounts authenticate with their OAuth access token in
 * {@link Account#password}. Graph and JMAP updates carry no If-Match
 * guard (last-write-wins); CardDAV and Google updates are guarded by
 * the ETag.
 */
public class PimalayaClient {
    /**
     * Discovers the CardDAV context root for the email through the
     * given DNS resolver (null for the default DNS-over-HTTPS one).
     * Throws when none is found.
     */
    public String discover(Transport transport, String email, String resolver) {
        JSONObject reply = object(Native.discover(transport, email, resolver));
        return string(reply, "url");
    }

    /**
     * Runs the MX provider probe: one DNS lookup matching the mail
     * exchanges against the fixed provider rules, catching every
     * domain whose mail lives at Google or Microsoft (gmail.com and
     * outlook.com included, their own exchanges match too). Meant to
     * run in parallel with {@link #searchAll} so the caller decides
     * what to take. Returns the raw provider configs (their source
     * naming the provider), empty when no rule matched. Resolver as
     * in {@link #discover}.
     */
    public List<ServiceConfig> searchProvider(Transport transport, String email, String resolver) {
        return configs(Native.searchProvider(transport, email, resolver));
    }

    /**
     * Searches every discovery mechanism for service configs of every
     * domain the app covers: the fixed provider rules, PACC, RFC 6764
     * CardDAV and CalDAV resolve, Mozilla autoconfig (IMAP and SMTP)
     * and RFC 8620 JMAP resolve, each carrying its endpoint and
     * authentication methods, so the connection screen can list what
     * the address actually offers. The input is an email address or a
     * bare domain (every mechanism is domain-driven; a domain just
     * skips the username hints). The mechanisms run in parallel, each
     * on its own transport; a failing mechanism is skipped. Their
     * outputs merge in mechanism-priority order, and the merged
     * configs' endpoints are probed in parallel for the authentication
     * schemes they actually advertise, a provider rule's excepted: what
     * it says is the provider's own word. Resolver as in
     * {@link #discover}.
     *
     * <p>The provider rules rank first, as io-pim-discovery's own sweep
     * ranks them: they name Google's and Microsoft's endpoints and sign-ins
     * for a custom domain as much as for their own, which nothing the
     * domain publishes does.
     */
    public List<ServiceConfig> searchAll(String email, String resolver) {
        String[] outputs = new String[6];
        Thread[] mechanisms = new Thread[outputs.length];
        for (int index = 0; index < mechanisms.length; index++) {
            final int mechanism = index;
            mechanisms[index] =
                    new Thread(
                            () -> {
                                Transport transport = new Transport();
                                try {
                                    outputs[mechanism] =
                                            searchMechanism(mechanism, transport, email, resolver);
                                } finally {
                                    transport.close();
                                }
                            });
            mechanisms[index].start();
        }
        joinAll(mechanisms);

        JSONArray lists = new JSONArray();
        for (String output : outputs) {
            lists.put(configLists(output));
        }
        JSONArray merged = array(Native.searchMerge(lists.toString()));

        String[] probed = new String[merged.length()];
        Thread[] probes = new Thread[probed.length];
        for (int index = 0; index < probes.length; index++) {
            final int at = index;
            final JSONObject found = object(merged, at);
            final String config = found.toString();
            if ("provider".equals(found.optString("source"))) {
                probed[at] = config;
                continue;
            }
            probes[index] =
                    new Thread(
                            () -> {
                                Transport transport = new Transport();
                                try {
                                    probed[at] = Native.searchProbe(transport, config);
                                } finally {
                                    transport.close();
                                }
                                try {
                                    object(probed[at]);
                                } catch (RuntimeException error) {
                                    // NOTE: a failed probe keeps the
                                    // config as discovered.
                                    probed[at] = config;
                                }
                            });
            probes[index].start();
        }
        joinAll(probes);

        JSONArray result = new JSONArray();
        for (String config : probed) {
            result.put(object(config));
        }
        return configs(result.toString());
    }

    /** One discovery mechanism run, by priority rank. */
    private static String searchMechanism(
            int mechanism, Transport transport, String email, String resolver) {
        switch (mechanism) {
            case 0:
                return Native.searchProvider(transport, email, resolver);
            case 1:
                return Native.searchPacc(transport, email, resolver);
            case 2:
                return Native.searchCarddav(transport, email, resolver);
            case 3:
                return Native.searchCaldav(transport, email, resolver);
            case 4:
                return Native.searchAutoconfig(transport, email, resolver);
            default:
                return Native.searchJmap(transport, email, resolver);
        }
    }

    /**
     * Parses one mechanism's output into a config array; a failed
     * mechanism (error reply, or none at all) is skipped as an empty
     * list, like the serial search chain did.
     */
    private static JSONArray configLists(String output) {
        if (output == null) {
            return new JSONArray();
        }
        try {
            return array(output);
        } catch (RuntimeException error) {
            return new JSONArray();
        }
    }

    /** Joins every thread started, restoring the interrupt flag if raised. */
    private static void joinAll(Thread[] threads) {
        for (Thread thread : threads) {
            if (thread == null) {
                continue;
            }
            try {
                thread.join();
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /**
     * Refreshes OAuth 2.0 tokens; {@code scope} is the space-separated
     * scope list of the original grant (null keeps the server default)
     * and {@code clientSecret} rides along when the registration
     * issued one (null for none). The code-exchange side lives on
     * {@link OauthSession}.
     */
    public OauthTokens oauthRefresh(Transport transport, String tokenEndpoint,
            String clientId,
            String clientSecret,
            String refreshToken,
            String scope) {
        JSONObject reply =
                object(
                        Native.oauthRefreshAccessToken(
                                transport,
                                tokenEndpoint,
                                clientId,
                                clientSecret == null ? "" : clientSecret,
                                refreshToken,
                                scope == null ? "" : scope));
        return OauthTokens.from(reply);
    }

    /**
     * Fetches an authorization server's RFC 8414 metadata from its
     * issuer, so onboarding can drive the code grant and tell whether
     * the server lets a public client register itself (RFC 7591).
     */
    public ServerMetadata oauthServerMetadata(Transport transport, String issuer) {
        return new ServerMetadata(object(Native.oauthServerMetadata(transport, issuer)));
    }

    /**
     * Registers a public client at the given RFC 7591 registration
     * endpoint (no secret, the loopback redirect URI, code + refresh
     * grants), returning the server-issued client id. The client
     * secret rides in {@link OauthTokens}-style JSON but a public
     * client gets none.
     */
    public String oauthRegisterClient(Transport transport, String registrationEndpoint, String redirectUri, String clientName, String scope) {
        JSONObject reply =
                object(
                        Native.oauthRegisterClient(
                                transport,
                                registrationEndpoint,
                                redirectUri,
                                clientName == null ? "" : clientName,
                                scope == null ? "" : scope));
        return string(reply, "client_id");
    }

    /**
     * Google's CardDAV principal root for the email; the standard
     * PROPFIND discovery runs from there.
     */
    public static String googleCarddavBase(String email) {
        return base("googleCarddav", email);
    }

    /** A Google API account's base URL for the email: People, Gmail or Calendar. */
    public static String googleBase(String email) {
        return base("google", email);
    }

    /** A Microsoft Graph account's base URL for the email. */
    public static String msgraphBase(String email) {
        return base("msgraph", email);
    }

    /**
     * A JMAP account's base URL wrapping the HTTPS session URL (a bare
     * host triggers {@code /.well-known/jmap} discovery).
     */
    public static String jmapBase(String sessionUrl) {
        return base("jmap", sessionUrl);
    }

    /**
     * What was typed for a submission server, as the endpoint it opens:
     * STARTTLS on the submission port (587, RFC 6409) and on 25, implicit
     * TLS on any other, 465 (RFC 8314 section 3.3) when none was typed. A
     * URL passes through.
     */
    public static String submitUrl(String entered) {
        if (entered.contains("://")) {
            return entered;
        }
        if (!entered.contains(":")) {
            return "smtps://" + entered + ":465";
        }
        String port = entered.substring(entered.lastIndexOf(':') + 1);
        return (port.equals("587") || port.equals("25") ? "smtp://" : "smtps://") + entered;
    }

    /**
     * The endpoint with a user in its userinfo, the way RFC 5092 section
     * 3.2 names who an IMAP URL signs in as: what SASL XOAUTH2 presents
     * beside a token, which carries no login of its own.
     */
    public static String withUser(String url, String user) {
        try {
            URI uri = URI.create(url);
            return new URI(
                            uri.getScheme(),
                            user,
                            uri.getHost(),
                            uri.getPort(),
                            uri.getPath(),
                            uri.getQuery(),
                            uri.getFragment())
                    .toASCIIString();
        } catch (URISyntaxException error) {
            throw new PimalayaException("Invalid endpoint '" + url + "': " + error.getMessage());
        }
    }

    /** Builds one account base URL through the bridge. */
    private static String base(String kind, String value) {
        return string(object(Native.accountBase(kind, value)), "url");
    }

    /**
     * True when the account's cards are account-level resources with
     * m:n addressbook memberships (JMAP, Google): they list once per
     * account through {@link #listAccountCards}, not per addressbook.
     * CardDAV and Graph cards live in the one collection they were
     * listed from and go through {@link #listCards}.
     */
    public static boolean isAccountLevel(Account account) {
        return account != null && isAccountLevel(account.baseUrl);
    }

    /**
     * True when the URL (an account base URL, or a collection URL
     * derived from one) belongs to an account-level backend.
     */
    public static boolean isAccountLevel(String url) {
        return info(url).optBoolean("accountLevel");
    }

    /** True when the URL belongs to a plain CardDAV backend. */
    public static boolean isCarddav(String url) {
        return "carddav".equals(info(url).optString("backend"));
    }

    /** True when the account's backend is Microsoft Graph. */
    public static boolean isGraph(Account account) {
        return account != null && "graph".equals(info(account.baseUrl).optString("backend"));
    }

    /** True when the account's backend is the Google People API. */
    public static boolean isGoogle(Account account) {
        return account != null && "google".equals(info(account.baseUrl).optString("backend"));
    }

    /** True when the account's backend speaks JMAP (RFC 8620). */
    public static boolean isJmap(Account account) {
        return account != null && "jmap".equals(info(account.baseUrl).optString("backend"));
    }

    /** Parsed backend info by URL, cached (pure computation). */
    private static final Map<String, JSONObject> infos = new ConcurrentHashMap<>();

    /** The URL's {@code {backend, accountLevel}} info. */
    private static JSONObject info(String url) {
        return infos.computeIfAbsent(
                url == null ? "" : url, key -> object(Native.accountInfo(key)));
    }

    /**
     * Lists the account's addressbooks: the CardDAV discovery walk,
     * the Graph contact folders (default Contacts folder first), the
     * JMAP AddressBooks, or the Google contact groups.
     */
    public List<Addressbook> listAddressbooks(Transport transport, Account account) {
        JSONArray reply =
                array(
                        Native.listAddressbooks(
                                transport, account.baseUrl, account.login, account.password));

        List<Addressbook> books = new ArrayList<>(reply.length());
        for (int index = 0; index < reply.length(); index++) {
            JSONObject book = object(reply, index);
            books.add(
                    new Addressbook(
                            string(book, "id"),
                            string(book, "name"),
                            string(book, "url"),
                            optString(book, "description"),
                            optString(book, "color")));
        }
        return books;
    }

    /**
     * The account's mailboxes with the roles their RFC 6154 attributes
     * mark, in one round.
     */
    public List<Mailbox> listMailboxes(MailSession session) {
        JSONArray reply =
                array(on(session, open -> Native.listMailboxes(open.transport(), open.handle())));

        List<Mailbox> mailboxes = new ArrayList<>(reply.length());
        for (int index = 0; index < reply.length(); index++) {
            JSONObject mailbox = object(reply, index);
            mailboxes.add(new Mailbox(string(mailbox, "name"), mailbox.optString("role")));
        }
        return mailboxes;
    }

    /**
     * One mailbox's spine from the cursor the last pass stored: which
     * messages moved and which went, and no envelopes.
     *
     * <p>An empty cursor is a full round over the newest {@code limit}
     * messages, which is what a first pass and a server without QRESYNC
     * both get; with one, the server streams the delta and the round says
     * so, so nothing it did not mention is retired.
     */
    public MailRound enumerateMailbox(
            MailSession session, String mailbox, String cursor, int limit) {
        JSONObject reply =
                object(
                        on(
                                session,
                                open ->
                                        Native.enumerateMailbox(
                                                open.transport(),
                                                open.handle(),
                                                mailbox,
                                                cursor == null ? "" : cursor,
                                                limit)));

        JSONArray listed = reply.optJSONArray("items");
        List<MailRef> items = new ArrayList<>(listed == null ? 0 : listed.length());
        for (int index = 0; listed != null && index < listed.length(); index++) {
            JSONObject item = object(listed, index);
            items.add(new MailRef(string(item, "id"), strings(item.optJSONArray("flags"))));
        }

        return new MailRound(
                items,
                strings(reply.optJSONArray("vanished")),
                reply.optBoolean("complete"),
                reply.optString("checkpoint"));
    }

    /** The envelope spine of the named messages, and of no others. */
    public List<Message> fetchEnvelopes(MailSession session, String mailbox, List<String> ids) {
        if (ids.isEmpty()) {
            return List.of();
        }
        String named = new JSONArray(ids).toString();
        JSONArray reply =
                array(
                        on(
                                session,
                                open ->
                                        Native.fetchEnvelopes(
                                                open.transport(),
                                                open.handle(),
                                                mailbox,
                                                named)));

        List<Message> messages = new ArrayList<>(reply.length());
        for (int index = 0; index < reply.length(); index++) {
            JSONObject message = object(reply, index);
            messages.add(
                    new Message(
                            string(message, "mailbox"),
                            string(message, "id"),
                            string(message, "subject"),
                            string(message, "from"),
                            string(message, "fromAddress"),
                            string(message, "date"),
                            message.optBoolean("seen"),
                            message.optBoolean("answered"),
                            message.optBoolean("flagged"),
                            message.optBoolean("hasAttachment")));
        }
        return messages;
    }

    /** The strings of a JSON array, empty when there is none. */
    private static List<String> strings(JSONArray values) {
        List<String> strings = new ArrayList<>(values == null ? 0 : values.length());
        for (int index = 0; values != null && index < values.length(); index++) {
            strings.add(values.optString(index));
        }
        return strings;
    }

    /**
     * Reads one message whole, as the bytes the server holds.
     *
     * <p>IMAP fetches it with {@code BODY.PEEK[]}; JMAP reads the
     * message's {@code blobId} and downloads that blob, which is one
     * round trip more than its body values would have been and the only
     * way to come back with the message rather than a rendering of it.
     * The caller stores what it gets and renders it through
     * {@link #parseMessage}, so a second open costs nothing.
     */
    public byte[] fetchMessageSource(MailSession session, String mailbox, String id) {
        JSONObject reply =
                object(
                        on(
                                session,
                                open ->
                                        Native.fetchMessageSource(
                                                open.transport(), open.handle(), mailbox, id)));
        return Base64.getDecoder().decode(reply.optString("source"));
    }

    /**
     * Resolves one message's MIME tree into what a reader draws: the
     * headers, the one body it shows, and what it carries beside it.
     *
     * <p>No network, whatever the message: this is the whole read path
     * of a message the store already holds.
     */
    public MessageBody parseMessage(byte[] source) {
        JSONObject reply = object(Native.parseMessage(source));

        JSONArray listed = reply.optJSONArray("attachments");
        List<MessageBody.Attachment> attachments =
                new ArrayList<>(listed == null ? 0 : listed.length());
        for (int index = 0; listed != null && index < listed.length(); index++) {
            JSONObject attachment = object(listed, index);
            attachments.add(
                    new MessageBody.Attachment(
                            attachment.optString("name"),
                            attachment.optString("mime"),
                            attachment.optLong("size")));
        }

        // NOTE: optString and not the null-returning helper beside it:
        // every one of these fields is always serialised, and a reader
        // must never be handed a null to render.
        return new MessageBody(
                reply.optString("subject"),
                reply.optString("from"),
                reply.optString("fromAddress"),
                reply.optString("to"),
                reply.optString("cc"),
                reply.optString("date"),
                reply.optString("kind"),
                reply.optString("body"),
                attachments);
    }

    /**
     * Adds or removes one marker on one message, named the IMAP way
     * whichever backend answers.
     */
    public void setMessageFlag(
            MailSession session, String mailbox, String id, String flag, boolean add) {
        object(
                on(
                        session,
                        open ->
                                Native.setMessageFlag(
                                        open.transport(),
                                        open.handle(),
                                        mailbox,
                                        id,
                                        flag,
                                        add)));
    }

    /**
     * Composes one draft into the RFC 5322 message an outbox holds.
     *
     * <p>{@code draft} is a JSON object of {@code from}, {@code fromName},
     * {@code to}, {@code cc}, {@code bcc}, {@code subject}, {@code body},
     * {@code date} and {@code messageId}: the composer's fields plus the
     * two stamps, which are the caller's so the bridge stays a pure
     * function of what it is handed.
     *
     * <p>No network, which is the point: a message is written and queued
     * with the radio off, and {@link #submitMessage} is what needs one.
     * The bytes carry a {@code Bcc} header, as RFC 5322 §3.6.3 provides
     * for a message prepared for sending; the submission takes it out.
     */
    public byte[] composeMessage(String draft) {
        JSONObject reply = object(Native.composeMessage(draft));
        return Base64.getDecoder().decode(reply.optString("source"));
    }

    /**
     * Hands one stored message over, then files the copy the sender
     * keeps, answering the mailbox it landed in or null when the account
     * named no sent mailbox.
     *
     * @throws SubmissionRefused when the server refused the message for
     *     good, which is what parks it rather than queueing it again.
     */
    public String submitMessage(MailSession session, byte[] source) {
        String submitUrl = session.account().submitUrl;
        // NOTE: run once, never retried, unlike every other verb on a
        // session. Sending is the one thing here that is not idempotent:
        // a submission that was accepted and then failed to file its copy
        // looks exactly like one that never went, and running it again
        // would send the message twice. A failure leaves it queued
        // instead, which is what the outbox is for.
        String json =
                Native.submitMessage(
                        session.transport(),
                        session.handle(),
                        submitUrl == null ? "" : submitUrl,
                        source);

        JSONObject reply = read(json);
        String error = reply.optString("error");
        if (!error.isEmpty()) {
            throw reply.optBoolean("permanent")
                    ? new SubmissionRefused(error)
                    : new PimalayaException(error);
        }
        return optString(reply, "mailbox");
    }

    /**
     * Deletes one message into the account's trash, answering the
     * mailbox it landed in, or null when the account named no trash and
     * the message was marked deleted where it is instead.
     */
    public String deleteMessage(MailSession session, String mailbox, String id) {
        JSONObject reply =
                object(
                        on(
                                session,
                                open ->
                                        Native.deleteMessage(
                                                open.transport(), open.handle(), mailbox, id)));
        return optString(reply, "mailbox");
    }

    /**
     * Opens one account's mail connection, greeted and authenticated, for
     * a caller that will run several verbs on it and close it.
     *
     * <p>The unit is the account and not the mailbox, which is what both
     * backends are shaped for: IMAP is one session over every mailbox and
     * JMAP one session resource over the whole account.
     */
    public static MailSession openMail(Account account) {
        Transport transport = new Transport();
        try {
            JSONObject reply =
                    object(
                            Native.openMailSession(
                                    transport,
                                    account.baseUrl,
                                    account.login,
                                    account.password));
            return new MailSession(account, transport, MailSession.handleOf(reply));
        } catch (RuntimeException failure) {
            transport.close();
            throw failure;
        }
    }

    /** One verb against an open session. */
    private interface OnSession {
        String run(MailSession session);
    }

    /**
     * Runs one idempotent verb on a held session, reopening it once if it
     * has died.
     *
     * <p>A held connection is a hint and never a promise: a server's idle
     * timeout, a rebound NAT and a walk from wifi to cellular all end one
     * under the app, and none of them announce it. So the first failure
     * is read as the connection rather than as the verb, and the verb is
     * given one honest attempt on a fresh session before its failure is
     * reported as its own.
     *
     * <p>Idempotent is the condition and not a description: a refusal and
     * a dead socket are not told apart here, so whatever runs through
     * this runs twice whenever a server says no. Re-reading a message,
     * re-storing a marker and re-moving a message that has already moved
     * all cost a round trip and change nothing. Submitting a message does
     * not, which is why it does not come through here.
     */
    private static String on(MailSession session, OnSession verb) {
        try {
            return verb.run(session);
        } catch (RuntimeException failure) {
            session.reopen();
            return verb.run(session);
        }
    }

    /** Lists the account's calendars: the CalDAV discovery walk. */
    public List<Calendar> listCalendars(Transport transport, Account account) {
        JSONArray reply =
                array(
                        Native.listCalendars(
                                transport, account.baseUrl, account.login, account.password));

        List<Calendar> calendars = new ArrayList<>(reply.length());
        for (int index = 0; index < reply.length(); index++) {
            JSONObject calendar = object(reply, index);
            calendars.add(
                    new Calendar(
                            string(calendar, "id"),
                            string(calendar, "name"),
                            string(calendar, "url"),
                            optString(calendar, "description"),
                            optString(calendar, "color")));
        }
        return calendars;
    }

    /**
     * Enumerates a calendar collection from the cursor the last pass
     * stored: which events moved and which went, and no bodies.
     *
     * <p>The bodies are {@link #multigetEvents}, for the events the merge
     * asks about. A round that carried them would be re-reading a whole
     * calendar to find out that nothing in it changed, which is what this
     * replaced.
     */
    public EventDelta syncEvents(
            Transport transport, Account account, String calendarUrl, String cursor) {
        JSONObject reply =
                object(
                        Native.syncEvents(
                                transport,
                                account.baseUrl,
                                calendarUrl,
                                account.login,
                                account.password,
                                cursor == null ? "" : cursor));

        JSONArray listed = reply.optJSONArray("changed");
        List<EventRef> changed = new ArrayList<>(listed == null ? 0 : listed.length());
        for (int index = 0; listed != null && index < listed.length(); index++) {
            JSONObject event = object(listed, index);
            changed.add(new EventRef(string(event, "id"), optString(event, "etag")));
        }

        return new EventDelta(
                changed,
                strings(reply.optJSONArray("vanished")),
                optString(reply, "token"),
                reply.optBoolean("complete"));
    }

    /** The iCalendar text of the named events, in one round. */
    public List<Event> multigetEvents(
            Transport transport, Account account, String calendarUrl, List<String> ids) {
        if (ids.isEmpty()) {
            return List.of();
        }
        JSONArray reply =
                array(
                        Native.multigetEvents(
                                transport,
                                account.baseUrl,
                                calendarUrl,
                                account.login,
                                account.password,
                                new JSONArray(ids).toString()));

        List<Event> events = new ArrayList<>(reply.length());
        for (int index = 0; index < reply.length(); index++) {
            JSONObject event = object(reply, index);
            events.add(
                    new Event(
                            string(event, "id"),
                            optString(event, "etag"),
                            string(event, "ical")));
        }
        return events;
    }

    /**
     * Expands one calendar object into the occurrences falling inside
     * {@code [windowStart, windowEnd)}, both civil stamps. Recurrence
     * is RFC 5545 complete (ical-rs); a non-recurring event yields at
     * most one occurrence, and one outside the window yields none.
     */
    public List<Occurrence> expandEvent(String ical, String windowStart, String windowEnd) {
        JSONArray reply = array(Native.expandEvent(ical, windowStart, windowEnd));

        List<Occurrence> occurrences = new ArrayList<>(reply.length());
        for (int index = 0; index < reply.length(); index++) {
            JSONObject occurrence = object(reply, index);
            occurrences.add(
                    new Occurrence(
                            string(occurrence, "component"),
                            string(occurrence, "start"),
                            string(occurrence, "end"),
                            string(occurrence, "summary"),
                            string(occurrence, "location"),
                            occurrence.optBoolean("allDay")));
        }
        return occurrences;
    }

    /**
     * Reads one calendar object's first scheduled component whole, for
     * the page that shows it. Pure computation, like the expansion
     * beside it: no transport, no account.
     */
    public EventDetail readEvent(String ical) {
        JSONObject reply = object(Native.readEvent(ical));

        JSONArray listed = reply.optJSONArray("attendees");
        List<EventDetail.Attendee> attendees = new ArrayList<>(listed == null ? 0 : listed.length());
        for (int index = 0; listed != null && index < listed.length(); index++) {
            JSONObject attendee = object(listed, index);
            attendees.add(
                    new EventDetail.Attendee(
                            attendee.optString("name"),
                            attendee.optString("address"),
                            attendee.optString("status")));
        }

        return new EventDetail(
                reply.optString("component"),
                reply.optString("uid"),
                reply.optString("summary"),
                reply.optString("description"),
                reply.optString("location"),
                reply.optString("url"),
                reply.optString("status"),
                reply.optString("categories"),
                reply.optString("start"),
                reply.optString("end"),
                reply.optString("due"),
                reply.optString("completed"),
                reply.optBoolean("allDay"),
                reply.optString("recurrence"),
                reply.optString("priority"),
                reply.optString("percentComplete"),
                reply.optString("organizer"),
                attendees,
                reply.optString("created"),
                reply.optString("lastModified"));
    }

    /**
     * Applies one edit to a calendar object, returning the new
     * iCalendar text. A patch through the object's concrete syntax
     * tree, so everything the edit does not name survives untouched.
     *
     * <p>{@code edit} is a JSON object of the properties to set; an
     * absent key is left alone and a present empty one removes its
     * property. Pure computation, no transport.
     */
    public String writeEvent(String ical, String edit) {
        String written = Native.writeEvent(ical, edit).trim();

        // NOTE: the reply is the object itself, so an error has to be
        // told apart from a body. It is: no calendar object opens with
        // a brace, so a brace is the bridge's error shape.
        if (written.startsWith("{")) {
            object(written);
            throw new PimalayaException("Unreadable bridge reply: expected an object");
        }
        return written;
    }

    /**
     * Pushes an edited object back to the calendar it came from,
     * guarded by the ETag it was read with, and returns the new one.
     *
     * <p>Guarded on purpose: a calendar is shared, and an unguarded PUT
     * is how one client silently overwrites another's edit.
     */
    public String updateEvent(Transport transport, Account account, String calendarUrl, String id, String ical, String etag) {
        String reply =
                Native.updateEvent(
                        transport,
                        account.baseUrl,
                        calendarUrl,
                        account.login,
                        account.password,
                        id,
                        ical,
                        etag == null ? "" : etag);

        String trimmed = reply.trim();
        if (trimmed.startsWith("{")) {
            // An object here is the bridge's error shape; a success
            // is the new ETag as a bare JSON string, or null.
            object(trimmed);
        }
        return trimmed.startsWith("\"")
                ? trimmed.substring(1, trimmed.length() - 1)
                : null;
    }

    /**
     * The object a new calendar entry starts from: one component
     * carrying the identity, the composition stamp and the day it is
     * placed on, and nothing else. Pure computation, no transport.
     */
    public String newEvent(String component, String uid, String stamp, String start) {
        String created = Native.newEvent(component, uid, stamp, start).trim();

        // NOTE: an object is the bridge's error shape here too, for the
        // same reason it is in writeEvent.
        if (created.startsWith("{")) {
            object(created);
            throw new PimalayaException("Unreadable bridge reply: expected an object");
        }
        return created;
    }

    /**
     * Files a new object in a calendar, guarded on the resource not
     * existing, and returns where it landed: the name asked for, or the
     * one a backend that names its own resources (Graph) gave it, with
     * its ETag.
     */
    public EventRef createEvent(Transport transport, Account account, String calendarUrl, String id, String ical) {
        JSONObject created =
                object(
                        Native.createEvent(
                                transport,
                                account.baseUrl,
                                calendarUrl,
                                account.login,
                                account.password,
                                id,
                                ical));
        try {
            return new EventRef(
                    created.getString("id"),
                    created.isNull("etag") ? null : created.getString("etag"));
        } catch (JSONException error) {
            throw new PimalayaException("Unreadable created event: " + error.getMessage());
        }
    }

    /**
     * Removes one object from its calendar, guarded by the ETag it was
     * read with, for the same reason the update is.
     */
    public void deleteEvent(Transport transport, Account account, String calendarUrl, String id, String etag) {
        object(
                Native.deleteEvent(
                        transport,
                        account.baseUrl,
                        calendarUrl,
                        account.login,
                        account.password,
                        id,
                        etag == null ? "" : etag));
    }

    /**
     * Lists every card of an account-level backend (JMAP, Google) in
     * one pass, each carrying its addressbook memberships as book ids
     * ({@link Card#books}).
     */
    public List<Card> listAccountCards(Transport transport, Account account) {
        return cards(
                Native.listAccountCards(
                        transport, account.baseUrl, account.login, account.password));
    }

    /** Lists the cards of the addressbook collection at the given URL. */
    public List<Card> listCards(Transport transport, Account account, String addressbookUrl) {
        return cards(
                Native.listCards(
                        transport,
                        account.baseUrl,
                        addressbookUrl,
                        account.login,
                        account.password));
    }

    /**
     * Lists a collection's changes since the given cursor, on every
     * backend: a CardDAV sync-collection REPORT (RFC 6578), a Graph
     * contacts delta round (id and changeKey only), a JMAP
     * ContactCard/changes round (changed cards in full), or a People
     * connections sync (changed contacts in full, account-wide with
     * memberships). A null cursor runs the initial round: the complete
     * member set plus the cursor to delta from next time. A cursor the
     * server no longer accepts re-runs an initial round bridge-side,
     * flagged {@link CardDelta#complete}.
     */
    public CardDelta syncCards(Transport transport, Account account, String addressbookUrl, String syncToken) {
        String reply =
                Native.syncCards(
                        transport,
                        account.baseUrl,
                        addressbookUrl,
                        account.login,
                        account.password,
                        syncToken == null ? "" : syncToken);

        JSONObject parsed = object(reply);
        JSONArray rows = parsed.optJSONArray("changed");
        List<Card> changed = new ArrayList<>(rows == null ? 0 : rows.length());
        for (int index = 0; rows != null && index < rows.length(); index++) {
            changed.add(card(object(rows, index)));
        }

        JSONArray gone = parsed.optJSONArray("vanished");
        List<String> vanished = new ArrayList<>(gone == null ? 0 : gone.length());
        for (int index = 0; gone != null && index < gone.length(); index++) {
            vanished.add(gone.optString(index));
        }

        return new CardDelta(
                changed,
                vanished,
                optString(parsed, "token"),
                parsed.optBoolean("complete"));
    }

    /**
     * Batch-fetches the cards at the given resource names inside a
     * CardDAV addressbook via REPORT addressbook-multiget.
     */
    public List<Card> multigetCards(Transport transport, Account account, String addressbookUrl, List<String> uris) {
        return cards(
                Native.multigetCards(
                        transport,
                        addressbookUrl,
                        account.login,
                        account.password,
                        new JSONArray(uris).toString()));
    }

    /**
     * Reconciles a collection with its remote through the io-offline
     * engine, the driver servicing every storage and remote yield;
     * with {@code full} the checkpoint is ignored and the whole remote
     * is enumerated. Returns the sync report
     * {@code {pulled, pushed, conflicts, rejected, refreshed}}.
     */
    public JSONObject offlineSync(OfflineDriver driver, String collection, boolean full) {
        return object(Native.offlineSync(driver, collection, full, true));
    }

    /**
     * The same for a collection whose bodies never change, mail: only
     * flags and membership are pushed, so a body a read stored is never
     * mistaken for an edit.
     */
    public JSONObject offlineSyncImmutable(OfflineDriver driver, String collection) {
        return object(Native.offlineSync(driver, collection, false, false));
    }

    /**
     * Raises the given handles to the full detail tier through the
     * io-offline engine (bodies deduped by link id against the store).
     * Returns the upgrade report {@code {upgraded, fetched, deduped}}.
     */
    public JSONObject offlineUpgrade(OfflineDriver driver, String collection, List<String> handles) {
        return object(
                Native.offlineUpgrade(driver, collection, new JSONArray(handles).toString(), true));
    }

    /**
     * Raises the given handles to the meta detail tier through the
     * io-offline engine: each is named and summarised, and no body is
     * read. Returns the upgrade report {@code {upgraded, fetched, deduped}}.
     */
    public JSONObject offlineUpgradeMeta(
            OfflineDriver driver, String collection, List<String> handles) {
        return object(
                Native.offlineUpgrade(driver, collection, new JSONArray(handles).toString(), false));
    }

    /**
     * Stages a local mutation through the io-offline engine (storage
     * yields only, the remote is never touched).
     */
    public void offlineMutate(OfflineDriver driver, String collection, JSONObject mutation) {
        object(Native.offlineMutate(driver, collection, mutation.toString()));
    }

    /**
     * Adds and removes the card's addressbook memberships on an
     * account-level backend, by book id: JMAP patches addressBookIds,
     * Google modifies group members.
     */
    public void updateCardBooks(Transport transport, Account account, String cardId, List<String> add, List<String> remove) {
        object(
                Native.updateCardBooks(
                        transport,
                        account.baseUrl,
                        account.login,
                        account.password,
                        cardId,
                        books(add),
                        books(remove)));
    }

    /**
     * Creates the card in the addressbook collection, returning it
     * with its ETag. The backends naming the resource themselves
     * return the server-assigned id instead of the given one.
     */
    public Card createCard(Transport transport, Account account, String addressbookUrl, String id, String vcard) {
        return card(
                object(
                        Native.createCard(
                                transport,
                                account.baseUrl,
                                addressbookUrl,
                                account.login,
                                account.password,
                                id,
                                vcard)));
    }

    /** Reads the card at the given resource name from the addressbook collection. */
    public Card readCard(Transport transport, Account account, String addressbookUrl, String uri) {
        return card(
                object(
                        Native.readCard(
                                transport,
                                account.baseUrl,
                                addressbookUrl,
                                account.login,
                                account.password,
                                uri)));
    }

    /**
     * Updates the card in the addressbook collection, returning it with
     * its new ETag. The base vCard (the state last synced with the
     * server, null when unknown) trims the Graph, JMAP and Google
     * patches to the fields the edit changed; CardDAV PUTs the full
     * vCard and ignores it.
     */
    public Card updateCard(Transport transport, Account account, String addressbookUrl, Card card, String baseVcard) {
        return card(
                object(
                        Native.updateCard(
                                transport,
                                account.baseUrl,
                                addressbookUrl,
                                account.login,
                                account.password,
                                card.id,
                                card.uri == null ? "" : card.uri,
                                card.vcard,
                                baseVcard == null ? "" : baseVcard,
                                card.etag == null ? "" : card.etag)));
    }

    /** Deletes the card from the addressbook collection. */
    public void deleteCard(Transport transport, Account account, String addressbookUrl, Card card) {
        object(
                Native.deleteCard(
                        transport,
                        account.baseUrl,
                        addressbookUrl,
                        account.login,
                        account.password,
                        card.id,
                        card.uri == null ? "" : card.uri,
                        card.etag == null ? "" : card.etag));
    }

    /**
     * Creates a batch of cards (Google-only: the one backend with a
     * batch create verb), returning the created cards in input order
     * with their server-assigned ids.
     */
    public List<Card> createCards(Transport transport, Account account, List<String> vcards) {
        return cards(
                Native.createCards(
                        transport,
                        account.baseUrl,
                        account.login,
                        account.password,
                        new JSONArray(vcards).toString()));
    }

    /** Deletes a batch of cards by id (Google-only, like createCards). */
    public void deleteCards(Transport transport, Account account, List<String> ids) {
        object(
                Native.deleteCards(
                        transport,
                        account.baseUrl,
                        account.login,
                        account.password,
                        new JSONArray(ids).toString()));
    }

    /**
     * Pushes a round of changes as batch calls (JMAP ContactCard/set,
     * Graph $batch), returning one {@code {ref, accepted, id?, etag?,
     * error?}} outcome per change: a rejected change reports rejected
     * instead of failing the round.
     */
    public JSONArray pushCards(Transport transport, Account account, String addressbookUrl, JSONArray changes) {
        return array(
                Native.pushCards(
                        transport,
                        account.baseUrl,
                        addressbookUrl,
                        account.login,
                        account.password,
                        changes.toString()));
    }

    /** Serializes a book id list for the bridge. */
    private static String books(List<String> ids) {
        return new JSONArray(ids).toString();
    }

    /** Parses a card-array reply. */
    private static List<Card> cards(String reply) {
        JSONArray parsed = array(reply);
        List<Card> cards = new ArrayList<>(parsed.length());
        for (int index = 0; index < parsed.length(); index++) {
            cards.add(card(object(parsed, index)));
        }
        return cards;
    }

    /** Parses a `{id, uri, etag, vcard, books?}` reply into a card. */
    private static Card card(JSONObject reply) {
        List<String> books = new ArrayList<>();
        JSONArray parsed = reply.optJSONArray("books");
        if (parsed != null) {
            for (int index = 0; index < parsed.length(); index++) {
                books.add(parsed.optString(index));
            }
        }

        return new Card(
                string(reply, "id"),
                string(reply, "uri"),
                optString(reply, "etag"),
                string(reply, "vcard"),
                books);
    }

    /** Parses a search reply into service configs. */
    private static List<ServiceConfig> configs(String json) {
        JSONArray reply = array(json);

        List<ServiceConfig> configs = new ArrayList<>(reply.length());
        for (int index = 0; index < reply.length(); index++) {
            configs.add(ServiceConfig.from(object(reply, index)));
        }
        return configs;
    }

    /**
     * Parses an object reply, surfacing the bridge's {@code error}
     * field along with the HTTP status riding it, when the failure
     * was an HTTP round.
     */
    static JSONObject object(String json) {
        JSONObject reply = read(json);
        String error = reply.optString("error");
        if (!error.isEmpty()) {
            throw new PimalayaException(error, reply.has("status") ? reply.optInt("status") : null);
        }
        return reply;
    }

    /**
     * Parses an object reply and hands it back whole, failure included.
     *
     * <p>For the one caller that reads more of a failure than its
     * message: a submission says whether it was refused, and
     * {@link #object} would have thrown before anything could look.
     */
    private static JSONObject read(String json) {
        try {
            return new JSONObject(json.trim());
        } catch (JSONException error) {
            throw new PimalayaException("Unreadable bridge reply: " + error.getMessage());
        }
    }

    /** Parses an array reply, surfacing the bridge's {@code error} field. */
    private static JSONArray array(String json) {
        String trimmed = json.trim();

        if (trimmed.startsWith("{")) {
            // NOTE: an object where an array is expected is always an error.
            object(trimmed);
            throw new PimalayaException("Unreadable bridge reply: expected an array");
        }

        try {
            return new JSONArray(trimmed);
        } catch (JSONException error) {
            throw new PimalayaException("Unreadable bridge reply: " + error.getMessage());
        }
    }

    private static JSONObject object(JSONArray array, int index) {
        try {
            return array.getJSONObject(index);
        } catch (JSONException error) {
            throw new PimalayaException("Unreadable bridge reply: " + error.getMessage());
        }
    }

    static String string(JSONObject object, String key) {
        try {
            return object.getString(key);
        } catch (JSONException error) {
            throw new PimalayaException("Unreadable bridge reply: " + error.getMessage());
        }
    }

    private static String optString(JSONObject object, String key) {
        return object.isNull(key) ? null : object.optString(key);
    }
}
