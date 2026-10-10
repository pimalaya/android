package org.pimalaya.client;

/**
 * The libpimalaya.so boundary. Every method blocks, drives the given
 * {@link Transport} for all socket I/O, and returns JSON: the documented
 * success shape, or {@code {"error": ".."}}. ETag parameters are plain
 * strings where empty means unknown (no If-Match guard). Every account
 * operation takes the account's base URL and dispatches on the backend
 * behind it in Rust; the base URL is opaque to Java. An empty login
 * means the password parameter carries an OAuth 2.0 access token, sent
 * as Bearer instead of Basic.
 */
final class Native {
    static {
        System.loadLibrary("pimalaya");
    }

    private Native() {}

    /**
     * RFC 6764 discovery: email to CardDAV context root, as
     * {@code {"url": ".."}}. The resolver is a {@code tcp://host:port}
     * DNS server or an RFC 8484 {@code https://…/dns-query} URL; empty
     * or null falls back to a public DNS-over-HTTPS one, which works on
     * mobile networks that block outbound DNS over TCP.
     */
    static native String discover(Transport transport, String email, String resolver);

    /**
     * Fixed provider rules: email to the provider's service configs,
     * matched by MX records (any domain whose mail lives at Google or
     * Microsoft, gmail.com and outlook.com included). Returns a JSON
     * array, empty when no rule matched. Resolver as in
     * {@link #discover}.
     */
    static native String searchProvider(Transport transport, String email, String resolver);

    /**
     * PACC discovery: the email domain's PACC document flattened into
     * service configs. Returns a JSON array. Resolver as in
     * {@link #discover}.
     */
    static native String searchPacc(Transport transport, String email, String resolver);

    /**
     * RFC 6764 resolve: the email domain's CardDAV context root as a
     * service config. Returns a JSON array. Resolver as in
     * {@link #discover}.
     */
    static native String searchCarddav(Transport transport, String email, String resolver);

    /**
     * RFC 6764 resolve: the email domain's CalDAV context root as a
     * service config. Returns a JSON array. Resolver as in
     * {@link #discover}.
     */
    static native String searchCaldav(Transport transport, String email, String resolver);

    /**
     * Mozilla autoconfig: the email domain's IMAP and SMTP endpoints as
     * service configs, from the ISP URLs, the ISPDB or the mailconf TXT
     * redirect. Returns a JSON array. Resolver as in {@link #discover}.
     */
    static native String searchAutoconfig(Transport transport, String email, String resolver);

    /**
     * RFC 8620 resolve: the email domain's JMAP session URL as a
     * service config. Returns a JSON array. Resolver as in
     * {@link #discover}.
     */
    static native String searchJmap(Transport transport, String email, String resolver);

    /**
     * Pure merge of per-mechanism config lists (a JSON array of
     * arrays, in mechanism-priority order) into one deduplicated list
     * restricted to the services the app drives (CardDAV, JMAP); no
     * transport. Returns a JSON array.
     */
    static native String searchMerge(String lists);

    /**
     * Probes one config's endpoints for the authentication schemes
     * they advertise on their unauthenticated 401 and refines the
     * config's password and bearer methods. Takes and returns one
     * config as JSON.
     */
    static native String searchProbe(Transport transport, String config);

    /**
     * Generates one authorization session's CSRF state and PKCE
     * verifier; pure computation, no transport. Returns
     * {@code {"state": "..", "verifier": ".."}}.
     */
    static native String oauthSessionParams();

    /**
     * The scope a contacts client should request of an authorization
     * server, negotiated against its space-separated advertised scopes
     * (empty for none); pure computation, no transport. Returns
     * {@code {"scope": ".."}}.
     */
    static native String oauthDomainScope(String scopesSupported, String domains);

    /**
     * Builds the OAuth 2.0 authorization URL with PKCE (S256) and CSRF
     * state; pure computation, no transport. {@code extras} is a JSON
     * object of provider-specific query parameters (empty for none).
     * Returns {@code {"url": ".."}}.
     */
    static native String oauthAuthorizeUrl(
            String authorizationEndpoint,
            String clientId,
            String redirectUri,
            String scope,
            String state,
            String pkceVerifier,
            String extras);

    /**
     * Validates the authorization redirect against the expected CSRF
     * state and extracts the authorization code; pure computation, no
     * transport. Returns {@code {"code": ".."}}.
     */
    static native String oauthValidateRedirect(String redirectUrl, String state);

    /**
     * Exchanges an authorization code for tokens against the token
     * endpoint, with the PKCE verifier of the authorization request
     * and the client secret when the registration issued one (empty
     * means none). Returns the RFC 6749 success params as JSON
     * ({@code access_token}, {@code token_type}, {@code expires_in},
     * {@code refresh_token}, {@code scope}, {@code issued_at}).
     */
    static native String oauthRequestAccessToken(
            Transport transport,
            String tokenEndpoint,
            String clientId,
            String clientSecret,
            String code,
            String redirectUri,
            String pkceVerifier);

    /**
     * Fetches an authorization server's RFC 8414 metadata from its
     * issuer (the {@code oauth-authorization-server} well-known,
     * falling back to the OpenID Connect Discovery document). Returns
     * the metadata JSON (issuer, endpoints, {@code registration_endpoint}
     * when the server supports RFC 7591, grants, scopes).
     */
    static native String oauthServerMetadata(Transport transport, String issuer);

    /**
     * Registers a public client at an RFC 7591 registration endpoint
     * ({@code token_endpoint_auth_method: none}, the given loopback
     * redirect URI, code + refresh grants, client name and scope).
     * Returns {@code {"client_id": "..", "client_secret": ".." | null}}.
     */
    static native String oauthRegisterClient(
            Transport transport,
            String registrationEndpoint,
            String redirectUri,
            String clientName,
            String scope);

    /**
     * Refreshes tokens against the token endpoint; {@code scope} is
     * the space-separated scope list of the original grant (empty
     * keeps the server default) and the client secret rides along when
     * the registration issued one (empty means none). Same JSON shape
     * as {@link #oauthRequestAccessToken}.
     */
    static native String oauthRefreshAccessToken(
            Transport transport,
            String tokenEndpoint,
            String clientId,
            String clientSecret,
            String refreshToken,
            String scope);

    /**
     * The backend behind an account base URL ({@code carddav},
     * {@code graph}, {@code jmap} or {@code google}) and whether its
     * cards are account-level resources with m:n addressbook
     * memberships, with the calendar and mail traits beside it; pure
     * computation, no transport. Returns {@code {"backend": "..",
     * "accountLevel": bool, "writesOverrides": bool, "writesExdates": bool,
     * "writesEvents": bool, "submitsOverSession": bool}}.
     */
    static native String accountInfo(String baseUrl);

    /**
     * Builds an account base URL of the given kind
     * ({@code googleCarddav}, {@code google}, {@code msgraph} from an
     * email, {@code jmap} from an HTTPS session URL or bare host);
     * pure computation, no transport. Returns {@code {"url": ".."}}.
     */
    static native String accountBase(String kind, String value);

    /**
     * The capability URNs a JMAP account's session serves an account
     * for, among mail, submission, contacts and calendars, from a fresh
     * session fetch. Returns a JSON array of URNs.
     */
    static native String jmapCapabilities(
            Transport transport, String baseUrl, String login, String password);

    /**
     * Lists the account's addressbooks, the backend dispatched from
     * the base URL. Returns a JSON array of addressbooks carrying
     * absolute collection URLs.
     */
    static native String listAddressbooks(
            Transport transport, String baseUrl, String login, String password);

    /**
     * The canonical pimdir SQL: every statement keyed by its constant name, as
     * a JSON object. Pure computation over compiled-in constants, so no
     * transport argument. See {@link PimdirSql} for why the statements come
     * from the crate rather than from Java.
     */
    static native String pimdirSql();

    /** The schema version the compiled-in pimdir SQL is. */
    static native int pimdirVersion();

    /**
     * Every canonical migration in order, as a JSON array of scripts, applied
     * above the store's {@code user_version} (STORAGE §6).
     */
    static native String pimdirMigrations();

    /**
     * What STORAGE Annex A derives from one body of a kind (its collection's
     * media type): {@code {linkId, summary, sortKey}}, the summary null where
     * the body yields none. Pure computation.
     */
    static native String pimdirDerive(String kind, byte[] body);

    /**
     * The account's mailboxes and the RFC 6154 role of each. Returns a
     * JSON array of {@code {name, role}} objects.
     *
     * <p>The role is {@code trash} where the server marks the mailbox with
     * the attribute of that name, and empty where it marks it another or
     * none. It rides along because a delete has to decide between a move
     * and a marker with no network to ask.
     */
    static native String listMailboxes(Transport transport, long session);

    /**
     * One page of a mailbox's listing, the engine's {@code enumerate}
     * yield answered (pimdir SYNC sections 4 and 5). {@code request} is
     * the yield's {@code {listing, scope}} as the engine wrote it, and
     * {@code covered} when the store's coverage holds the scope. Returns the
     * reply the engine reads: {@code {items, vanished, complete, last,
     * cursor?, checkpoint?}}, every item {@code {handle, flags, linkId,
     * summary, sortKey}} named by its meta but a Graph delta's, listed by
     * {@code {handle, flags, linkId}} alone for the caller to name where the
     * store binds it not ({@link #nameMessages}), or {@code
     * {cursorRejected: true}} when the source refused the resume cursor.
     */
    static native String enumerateMailbox(
            Transport transport, long session, String mailbox, String request);

    /**
     * The messages a listing named by id and markers alone (a Graph delta),
     * each read with its summary. {@code handles} is a JSON array of ids;
     * returns {@code {items}}, every item named as a listed one is, a
     * message gone since it was listed left out.
     */
    static native String nameMessages(
            Transport transport, long session, String mailbox, String handles);

    /**
     * The floor of a mailbox's next chunk: the oldest {@code Date} among its
     * {@code count} newest messages dated before {@code before} (RFC 3339
     * {@code Z}, empty for no ceiling). Returns {@code {floor, dated}},
     * {@code floor} null when fewer than {@code count} dated messages lie
     * below the ceiling.
     */
    static native String mailFloor(
            Transport transport, long session, String mailbox, String before, int count);

    /**
     * Connects to the account's mail server and authenticates, answering
     * {@code {handle}}, the bridge session the other mail verbs run on.
     *
     * <p>The handle is a pointer: one owner, one thread at a time, freed
     * once with {@link #closeMailSession}. {@link MailSession} is that
     * owner.
     */
    static native String openMailSession(
            Transport transport, String url, String login, String password);

    /** Frees the session a handle names; the sockets are the caller's. */
    static native void closeMailSession(long session);

    /**
     * Has a session work for the pool run numbered {@code run}, sharing with
     * the run's other sessions what it reads of a Gmail account (its listing,
     * envelopes and history), dropped with the run; 0 leaves the run.
     */
    static native void joinMailRun(long session, long run);

    /**
     * Reads one message whole, as the RFC 5322 bytes the server holds.
     * Returns a JSON object of {@code {source}}, the message
     * base64-encoded: a Java string is UTF-8 and a message is not, so
     * the bytes travel encoded and are stored decoded.
     *
     * <p>The mailbox is only IMAP's concern: a JMAP {@code Email} id
     * addresses the message across the whole account.
     */
    static native String fetchMessageSource(
            Transport transport, long session, String mailbox, String id);

    /**
     * Resolves one message's MIME tree into what a reader draws. Returns
     * a JSON object of {@code {subject, from, fromAddress, to, cc, date,
     * kind, body, attachments}}.
     *
     * <p>No transport: the bytes are the argument, so a message the
     * store already holds is read with no network at all.
     */
    static native String parseMessage(byte[] source);

    /**
     * Writes the decoded bytes of one part of the message stored at
     * {@code source}, by its IMAP section, to the file {@code target}.
     * Returns a JSON object of {@code {size}}, or an error when the message
     * holds no such part. No transport, as {@link #parseMessage}.
     */
    static native String messagePart(String source, String part, String target);

    /**
     * Adds or removes one marker on one message, named the IMAP way
     * ({@code \Seen}, {@code \Answered}, {@code \Flagged}) whichever
     * backend answers: JMAP's keywords map onto the same three (RFC 8621
     * §4.1.1). Returns an empty JSON object.
     */
    static native String setMessageFlag(
            Transport transport,
            long session,
            String mailbox,
            String id,
            String flag,
            boolean add);

    /**
     * Composes one draft into the RFC 5322 message an outbox holds.
     * Returns a JSON object of {@code {source}}, the message
     * base64-encoded.
     *
     * <p>No transport: composing reaches for nothing, which is what lets
     * a message be written and queued with the radio off. The bytes
     * carry a {@code Bcc} header, which RFC 5322 §3.6.3 provides for a
     * message prepared for sending; {@link #submitMessage} takes it back
     * out.
     */
    static native String composeMessage(String draft);

    /**
     * Hands one stored message over: the submit URL is where it goes, on a
     * connection of its own, or the session's own API on Graph and Gmail.
     * The envelope comes off the message's own address headers, the
     * {@code Bcc} among them, and that header leaves the bytes on the way
     * out. Returns an empty JSON object, or the error and whether it is
     * permanent.
     */
    static native String submitMessage(
            Transport transport, long session, String submitUrl, byte[] source);

    /**
     * Moves one message from {@code mailbox} into {@code target}, the push
     * of a removal naming a destination (pimdir SYNC §4). Returns an empty
     * JSON object.
     */
    static native String relocateMessage(
            Transport transport, long session, String mailbox, String id, String target);

    /**
     * Copies one message from {@code mailbox} into {@code target} on the
     * server, the push of a create naming an origin. Returns an empty JSON
     * object.
     */
    static native String copyMessage(
            Transport transport, long session, String mailbox, String id, String target);

    /**
     * Deletes one message for good, the push of a removal naming no
     * destination: on IMAP {@code \Deleted} then {@code UID EXPUNGE} with
     * UIDPLUS (RFC 4315), the marker alone otherwise. Returns an empty JSON
     * object.
     */
    static native String destroyMessage(
            Transport transport, long session, String mailbox, String id);

    /**
     * Appends one message to {@code mailbox} with {@code flags} (a JSON
     * array of markers), the push of a create naming no origin. IMAP only.
     * Returns an empty JSON object.
     */
    static native String appendMessage(
            Transport transport, long session, String mailbox, byte[] source, String flags);

    /**
     * Lists the account's calendars off its base URL, CalDAV or JMAP.
     * Returns a JSON array of calendars carrying the collection URL
     * every event listing addresses.
     */
    static native String listCalendars(
            Transport transport, String baseUrl, String login, String password);

    /**
     * Enumerates a calendar collection from the cursor the last pass
     * stored, answering which events moved and no bodies. Returns
     * {@code {changed, vanished, token, complete}}, each change
     * {@code {id, etag}}.
     *
     * <p>An empty cursor is an initial round. A cursor the server
     * rejects is re-run as one, and {@code complete} says so, so a
     * rejected round is never read as an emptied calendar.
     *
     * <p>Takes the account's base URL beside the collection's: it is
     * what names the backend, and a JMAP calendar's URL is an id behind
     * the account marker rather than something addressable on its own.
     */
    static native String syncEvents(
            Transport transport,
            String baseUrl,
            String url,
            String login,
            String password,
            String cursor);

    /**
     * The iCalendar text of the named events, in one round. {@code ids}
     * is a JSON array of strings. Returns a JSON array of
     * {@code {id, etag, ical}}.
     */
    static native String multigetEvents(
            Transport transport,
            String baseUrl,
            String url,
            String login,
            String password,
            String ids);

    /**
     * The occurrences one calendar object denotes inside a civil
     * window, its recurrence set composed. Pure computation, no
     * transport, so no {@link Transport} argument. Returns a JSON array
     * of {@code {component, start, end, recurrenceId, summary, location,
     * allDay}}, each time a {@code {time, kind, tzid, offset}}.
     */
    static native String expandEvent(String ical, String windowStart, String windowEnd);

    /**
     * One calendar object read whole, for the page that shows it: the
     * series, or the override of the occurrence a non-empty
     * {@code recurrenceId} names. Pure computation, no transport, like
     * the expansion beside it. Returns a JSON object of the component's
     * properties.
     */
    static native String readEvent(String ical, String recurrenceId);

    /**
     * Applies one edit to a calendar object and returns the new
     * iCalendar text, patched through its concrete syntax tree so
     * everything the edit does not name survives. Pure computation, no
     * transport. Returns the object itself, or a JSON error object.
     */
    static native String writeEvent(String ical, String edit);

    /**
     * Splits a series at the occurrence an edit names. Pure computation,
     * no transport. Returns {@code {master, series}}, {@code series}
     * null when the occurrence was the first, or a JSON error object.
     */
    static native String splitEvent(String ical, String edit);

    /**
     * Removes the occurrences an edit's scope names from a series. Pure
     * computation, no transport. Returns the object left, an empty
     * string when nothing is, or a JSON error object.
     */
    static native String removeEvent(String ical, String edit);

    /**
     * Three-way merges a conflicted calendar object, the body staged here
     * and the one its source holds against their base (empty when none
     * was agreed). Pure computation, no transport. Returns
     * {@code {ical, resolved, conflicts}}, or a JSON error object.
     */
    static native String mergeEvent(String base, String local, String remote);

    /**
     * The resolution of a conflicted calendar object: its merge, each
     * conflict {@code picks} names (a JSON object of side by conflict id)
     * taking that side. Pure computation, no transport. Returns the
     * object itself, or a JSON error object.
     */
    static native String resolveEvent(String base, String local, String remote, String picks);

    /**
     * One calendar object as the phone's calendar provider carries it
     * (docs/calendar-mapping.md), {@code now} a UTC stamp placing the
     * window a series the provider cannot show is listed over. Pure
     * computation, no transport. Returns {@code {uid, master, overrides,
     * listed}}, or a JSON error object.
     */
    static native String projectEvent(String ical, String now);

    /**
     * Patches a view the phone edited onto a calendar object, the fields
     * it changed alone; an empty object becomes a new one. Pure
     * computation, no transport. Returns the object itself, or a JSON
     * error object.
     */
    static native String applyEvent(String ical, String edit);

    /**
     * Pushes an edited object back to its calendar, guarded by
     * {@code etag} when one is known, {@code address} the account's, which
     * Google and JMAP announce a meeting the user organizes by. Returns the
     * new ETag as a JSON string (or null), or a JSON error object.
     */
    static native String updateEvent(
            Transport transport,
            String baseUrl,
            String calendarUrl,
            String login,
            String password,
            String id,
            String ical,
            String etag,
            String address);

    /**
     * The object a new calendar entry starts from, carrying the three
     * properties RFC 5545 requires of its component, its start in the
     * zone {@code tzid} names (none for a date or a floating time) with
     * that zone's {@code vtimezone}. Pure computation, no transport.
     * Returns the object itself, or a JSON error object.
     */
    static native String newEvent(
            String component,
            String uid,
            String stamp,
            String start,
            String tzid,
            String vtimezone);

    /**
     * Files a new object in a calendar, guarded on the resource not
     * existing, {@code address} as {@link #updateEvent} takes it. Returns
     * the new ETag as a JSON string (or null), or a JSON error object.
     */
    static native String createEvent(
            Transport transport,
            String baseUrl,
            String calendarUrl,
            String login,
            String password,
            String id,
            String ical,
            String address);

    /**
     * Removes one object from its calendar, guarded by {@code etag} when
     * one is known, {@code address} as {@link #updateEvent} takes it.
     * Returns an empty JSON object, or a JSON error one.
     */
    static native String deleteEvent(
            Transport transport,
            String baseUrl,
            String calendarUrl,
            String login,
            String password,
            String id,
            String etag,
            String address);

    /**
     * Lists every card of an account-level backend (JMAP, Google) in
     * one pass, each carrying its addressbook memberships as book ids.
     * Returns a JSON array of {@code {id, uri, etag, vcard, books}}.
     */
    static native String listAccountCards(
            Transport transport, String baseUrl, String login, String password);

    /**
     * Lists the cards of the addressbook collection (CardDAV, Graph),
     * the backend dispatched from the base URL. Returns a JSON array
     * of {@code {id, uri, etag, vcard}}.
     */
    static native String listCards(
            Transport transport,
            String baseUrl,
            String addressbookUrl,
            String login,
            String password);

    /**
     * Creates the card in the addressbook collection, the backend
     * dispatched from the base URL. Returns the created
     * {@code {id, uri, etag, vcard}} (the server-assigned id on the
     * backends naming the resource themselves).
     */
    static native String createCard(
            Transport transport,
            String baseUrl,
            String addressbookUrl,
            String login,
            String password,
            String id,
            String vcard);

    /**
     * Reads the card at the given resource name (as the server
     * returned it), the backend dispatched from the base URL; returns
     * {@code {id, uri, etag, vcard}}.
     */
    static native String readCard(
            Transport transport,
            String baseUrl,
            String addressbookUrl,
            String login,
            String password,
            String uri);

    /**
     * Updates the card at the given resource name (empty falls back to
     * the id), the backend dispatched from the base URL. The base
     * vCard trims the patching backends' updates to the fields the
     * edit changed and the ETag guards the guarding backends' writes;
     * returns the updated {@code {id, uri, etag, vcard}}.
     */
    static native String updateCard(
            Transport transport,
            String baseUrl,
            String addressbookUrl,
            String login,
            String password,
            String id,
            String uri,
            String vcard,
            String baseVcard,
            String etag);

    /**
     * Deletes the card at the given resource name (empty falls back to
     * the id), the backend dispatched from the base URL and the ETag
     * guarding the guarding backends' deletion; returns {@code {}}.
     */
    static native String deleteCard(
            Transport transport,
            String baseUrl,
            String addressbookUrl,
            String login,
            String password,
            String id,
            String uri,
            String etag);

    /**
     * Creates a batch of cards (a JSON array of vCard documents) in
     * the addressbook collection, Google-only (the one backend with a
     * batch create verb); returns a JSON array of the created
     * {@code {id, uri, etag, vcard}} in input order.
     */
    static native String createCards(
            Transport transport, String baseUrl, String login, String password, String vcards);

    /**
     * Deletes a batch of cards (a JSON array of card ids) from the
     * addressbook collection, Google-only (the one backend with a
     * batch delete verb); returns {@code {}}.
     */
    static native String deleteCards(
            Transport transport, String baseUrl, String login, String password, String ids);

    /**
     * Pushes a round of changes (a JSON array of {@code {ref, op, id?,
     * vcard?, baseVcard?, add?, remove?}}) to the addressbook
     * collection as batch calls, JMAP (ContactCard/set) and Graph
     * ($batch) only; returns a JSON array of {@code {ref, accepted,
     * id?, etag?, error?}}, one outcome per change.
     */
    static native String pushCards(
            Transport transport,
            String baseUrl,
            String addressbookUrl,
            String login,
            String password,
            String changes);

    /**
     * Lists the collection's changes since the given cursor (empty
     * runs the initial round: the complete member set plus the cursor
     * to delta from next time), the backend dispatched from the base
     * URL. An expired cursor re-runs an initial round and an initial
     * CardDAV sync a server rejects falls back to the plain
     * enumeration, both bridge-side. Returns {@code {"changed": [{id,
     * uri, etag, vcard?, books?}], "vanished": [uri], "token": "..",
     * "complete": bool}}.
     */
    static native String syncCards(
            Transport transport,
            String baseUrl,
            String addressbookUrl,
            String login,
            String password,
            String syncToken);

    /**
     * Batch-fetches the cards at the given resource names (a JSON
     * string array) via REPORT addressbook-multiget; CardDAV only.
     * Returns a JSON array of {@code {id, uri, etag, vcard}}.
     */
    static native String multigetCards(
            Transport transport,
            String addressbookUrl,
            String login,
            String password,
            String uris);

    /**
     * Reads the Graph contacts named by id (a JSON string array), 20 to
     * a {@code $batch}, the request line of {@link #readCard}; a request
     * the batch could not serve is sent again on its own and a contact
     * Graph no longer holds is left out. Graph only. Returns a JSON array
     * of {@code {id, uri, etag, vcard}}.
     */
    static native String readGraphCards(Transport transport, String token, String ids);

    /**
     * Reads the People contacts named by id (a JSON string array), 200 to
     * a {@code people:batchGet}, the read of {@link #readCard}; a contact
     * Google no longer holds is left out. Google only. Returns a JSON
     * array of {@code {id, uri, etag, vcard, books}}.
     */
    static native String readGoogleCards(Transport transport, String token, String ids);

    /**
     * Adds and removes the card's addressbook memberships on an
     * account-level backend (JSON string arrays of book ids), the
     * backend dispatched from the base URL; returns {@code {}}.
     */
    static native String updateCardBooks(
            Transport transport,
            String baseUrl,
            String login,
            String password,
            String id,
            String add,
            String remove);

    /**
     * Reconciles the collection with its remote through the io-offline
     * engine, servicing every engine yield via the driver; with
     * {@code full} the checkpoint is ignored and the whole remote is
     * enumerated; without {@code content} no body is pushed. {@code since}
     * bounds a mail collection's scope on the {@code Date} header (RFC
     * 3339 {@code Z}, empty for none), and {@code scopeBound} says whether
     * the connector's checkpoint is bound to the scope it was made under.
     * Returns the sync report
     * {@code {pulled, pushed, conflicts, rejected, refreshed, waiting}}.
     */
    static native String offlineSync(
            OfflineDriver driver,
            String collection,
            boolean full,
            boolean content,
            String since,
            boolean scopeBound);

    /**
     * Raises the given handles (a JSON string array) to the full
     * detail tier, or to the meta one without {@code full}, through the
     * io-offline engine, servicing every engine yield via the driver.
     * Returns the upgrade report {@code {upgraded, fetched, deduped}}.
     */
    static native String offlineUpgrade(
            OfflineDriver driver, String collection, String handles, boolean full);

    /**
     * Stages a local mutation (a JSON object, e.g. {@code {"op":
     * "edit", handle, hash, size, body, meta}}) through the io-offline
     * engine, servicing the storage yields via the driver; the remote
     * is never touched. Returns {@code {}}.
     */
    static native String offlineMutate(OfflineDriver driver, String collection, String mutation);

    /**
     * Whether a 412-rejected push may retry unguarded, the last
     * enumerate proving the handle unchanged (the CardDAV If-Match
     * quirk); pure computation, no transport. Takes {@code {listed:
     * {handle: etag}?, complete, handle, ifMatch?}}, returns
     * {@code {"retry": bool}}.
     */
    static native String offlineRetryUnguarded(String facts);

    /**
     * Projects an account-wide delta (JMAP, Google) onto one book's
     * enumerate; pure computation, no transport. Takes {@code {bookId?,
     * complete, changed: [{handle, books, known}], vanished}}, returns
     * {@code {"members": [index], "vanished": [handle]}}.
     */
    static native String offlineAccountSnapshot(String facts);

    /**
     * Plans one push change (membership patch vs create or delete,
     * plus the Google post-create membership); pure computation, no
     * transport. Takes {@code {op, collection, bookId?, origin,
     * deleted}}, returns {@code {action, postCreateBooks?}}.
     */
    static native String offlinePushPlan(String facts);

    /**
     * Indexes a vCard for the store (display name, first email and
     * phone, every email, UID, normalized content hash); pure
     * computation, no transport. Returns
     * {@code {name, email, emails, phone, info, uid, hash}}.
     */
    static native String indexCard(String vcard);

    /**
     * Merges several vCards (a JSON string array) into one union
     * document with its field model and per-field alternatives; pure
     * computation, no transport. Returns
     * {@code {vcard, model, alternatives}}.
     */
    static native String mergeCards(String cards);

    /**
     * Three-way merges a conflicted push: the staged local edit and
     * the fetched remote card against their common base, the local
     * side winning same-field collisions; pure computation, no
     * transport. Returns {@code {vcard, conflicts}}.
     */
    static native String mergeCardChanges(String base, String local, String remote);

    /**
     * Builds the conflict form's inputs for a both-sides-edited row: the
     * three-way merge with the newer side (by REV) winning collisions as
     * the pre-filled default, and the two candidates per genuinely
     * conflicted field; pure computation, no transport. Returns
     * {@code {vcard, model, alternatives, changed}}.
     */
    static native String mergeConflictForm(String base, String local, String remote);

    /**
     * Rewrites the card's UID (a plain copy is a new identity); pure
     * computation, no transport. Returns {@code {"vcard": ".."}}.
     */
    static native String setCardUid(String vcard, String uid);

    /**
     * Finds groups of likely-duplicate cards (exact normalized email,
     * phone or name matches); pure computation, no transport. Takes a
     * JSON array of {@code {ref, vcard}} pairs, returns
     * {@code {"groups": [{"refs": [...], "reasons": [...]}]}}.
     */
    static native String findDuplicates(String cards);

    /**
     * Lists the card's raw property lines for the advanced editor;
     * pure computation, no transport. Returns
     * {@code {"props": ["VERSION:4.0", ...]}}.
     */
    static native String cardProps(String vcard);

    /**
     * Rewrites one raw property line for the advanced editor (a blank
     * line removes, index -1 appends); pure computation, no transport.
     * Returns {@code {"vcard": ".."}}.
     */
    static native String cardSetProp(String vcard, int index, String line);

    /**
     * Recomposes one property from its structured parts
     * ({@code {name, params: [{name, values}], value}}) and rewrites
     * it (index -1 appends); pure computation, no transport. Returns
     * {@code {"vcard": ".."}}.
     */
    static native String cardSetPropParts(String vcard, int index, String prop);

    /**
     * The component labels of a structured property name (N, ADR,
     * GENDER; empty for plain values), shaping the advanced editor's
     * value form; pure computation, no transport. Returns
     * {@code {"labels": [...]}}.
     */
    static native String cardPropLabels(String name);

    /**
     * The ordered type-set vocabulary the edit form's spinners address
     * for the kind ({@code phone}, {@code email}, {@code address},
     * {@code relation}, {@code gender}), each position's vCard TYPE set
     * in the order the Android string-arrays must mirror; pure
     * computation, no transport. Returns {@code {"order": [[..], ..]}}.
     */
    static native String cardTypeOrder(String kind);

    /**
     * Validates a hand-edited vCard source (it must reparse) and
     * returns it re-serialized; pure computation, no transport.
     * Returns {@code {"vcard": ".."}}.
     */
    static native String cardSource(String vcard);

    /**
     * Projects a vCard onto the neutral field model the app maps to
     * ContactsContract rows; pure computation, no transport.
     */
    static native String projectCard(String vcard);

    /**
     * Patches an edited field model back onto the vCard, preserving
     * every unmanaged property; pure computation, no transport. Returns
     * {@code {"vcard": ".."}}.
     */
    static native String applyCard(String vcard, String model);

    /**
     * The edit form's view support computed from the field model
     * (summaries, type spinner positions, picker dates); pure
     * computation, no transport. Returns {@code {name, organization,
     * gender?, birthday?, anniversary?, phones, emails, relations,
     * addresses}}.
     */
    static native String formView(String model);

    /**
     * One typed entry saved from an edit dialog, its TYPE set drawn
     * from the spinner position ({@code phone}, {@code email} and
     * {@code relation} return the full entry, {@code address} the TYPE
     * set alone, {@code gender} the GENDER object, empty when unset);
     * pure computation, no transport.
     */
    static native String formEntry(String kind, int index, String value, boolean pref);

    /**
     * One picked date on the model wire (the vCard {@code yyyy-mm-dd}
     * form, 1-based month); pure computation, no transport. Returns
     * {@code {"value": ".."}}.
     */
    static native String formDate(int year, int month, int day);

    /**
     * Groups the replica pool into merged contacts, the groups sorted
     * by primary display name; pure computation, no transport. Takes
     * {@code {replicas: [{ref, uid, name, id}], links: {member:
     * cluster}, detached: [ref]}}, returns {@code {"groups": [{key,
     * replicas: [index]}]}}.
     */
    static native String groupContacts(String input);

    /**
     * The duplicate review's group facts, the dismissal key and the
     * Link eligibility; pure computation, no transport. Takes
     * {@code [{ref, book}]}, returns {@code {key, linkable}}.
     */
    static native String duplicateGroup(String members);
}
