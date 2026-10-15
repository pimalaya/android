package org.pimalaya.client;

/**
 * The libpimalaya.so boundary: blocking calls answering JSON or an error.
 *
 * <p>A reply is the documented shape or {@code {"error": ".."}}; a method
 * taking no {@link Transport} is pure. A network verb takes the account as
 * {@link Account#toJson}, its base URL opaque, dispatched in Rust, an empty
 * login meaning a Bearer token; an empty ETag means no If-Match.
 */
final class Native {
    static {
        System.loadLibrary("pimalaya");
        // NOTE: the library is built in release whatever the variant, so
        // only this side knows a debug app, whose logs go verbose.
        debugLogs(BuildConfig.DEBUG);
    }

    private Native() {}

    /**
     * Fixed provider rules: the configs of the provider the MX names.
     *
     * <p>Google or Microsoft, custom domains included. Returns a JSON array,
     * empty when no rule matched. The resolver is a {@code tcp://host:port}
     * or RFC 8484 URL; empty or null is a public DNS-over-HTTPS one, which
     * mobile networks blocking DNS over TCP still reach.
     */
    static native String searchProvider(Transport transport, String email, String resolver);

    /**
     * PACC discovery: the domain's PACC document as configs, a JSON array.
     *
     * <p>Resolver as in {@link #searchProvider}.
     */
    static native String searchPacc(Transport transport, String email, String resolver);

    /**
     * RFC 6764 resolve: the domain's CardDAV root as configs, a JSON array.
     *
     * <p>Resolver as in {@link #searchProvider}.
     */
    static native String searchCarddav(Transport transport, String email, String resolver);

    /**
     * RFC 6764 resolve: the domain's CalDAV root as configs, a JSON array.
     *
     * <p>Resolver as in {@link #searchProvider}.
     */
    static native String searchCaldav(Transport transport, String email, String resolver);

    /**
     * Mozilla autoconfig: the domain's IMAP and SMTP configs, a JSON array.
     *
     * <p>From the ISP URLs, the ISPDB or the mailconf TXT redirect. Resolver
     * as in {@link #searchProvider}.
     */
    static native String searchAutoconfig(Transport transport, String email, String resolver);

    /**
     * RFC 8620 resolve: the domain's JMAP session URL as configs, a JSON array.
     *
     * <p>Resolver as in {@link #searchProvider}.
     */
    static native String searchJmap(Transport transport, String email, String resolver);

    /**
     * Merges per-mechanism config arrays, in priority order, into one array.
     *
     * <p>Deduplicated and restricted to the services the app drives.
     */
    static native String searchMerge(String lists);

    /**
     * Probes one JSON config's endpoints for their 401 auth schemes.
     *
     * <p>Returns the config, its password and bearer methods refined.
     */
    static native String searchProbe(Transport transport, String config);

    /**
     * Generates a session's CSRF state and PKCE verifier.
     *
     * <p>Returns {@code {"state": "..", "verifier": ".."}}.
     */
    static native String oauthSessionParams();

    /**
     * The scope to request for {@code domains}, against the advertised ones.
     *
     * <p>Space-separated, empty for none. Returns {@code {"scope": ".."}}.
     */
    static native String oauthDomainScope(String scopesSupported, String domains);

    /**
     * Builds the OAuth 2.0 authorization URL with PKCE (S256) and CSRF state.
     *
     * <p>{@code extras} is a JSON object of provider query parameters, empty
     * for none. Returns {@code {"url": ".."}}.
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
     * Checks a redirect's CSRF state and extracts its code.
     *
     * <p>Also its RFC 9207 {@code iss} when {@code issuer} is not empty.
     * Returns {@code {"code": ".."}}.
     */
    static native String oauthValidateRedirect(String redirectUrl, String state, String issuer);

    /**
     * Exchanges a code for tokens, with the PKCE verifier and any secret.
     *
     * <p>An empty secret is none. Returns RFC 6749's params: access_token,
     * token_type, expires_in, refresh_token, scope, issued_at.
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
     * Fetches an issuer's RFC 8414 metadata, else its OpenID Connect one.
     *
     * <p>Returns issuer, endpoints, {@code registration_endpoint} where RFC
     * 7591 is supported, grants and scopes.
     */
    static native String oauthServerMetadata(Transport transport, String issuer);

    /**
     * Registers a public client (RFC 7591), no auth method, code and refresh.
     *
     * <p>Returns {@code {"client_id": "..", "client_secret": ".." | null}}.
     */
    static native String oauthRegisterClient(
            Transport transport,
            String registrationEndpoint,
            String redirectUri,
            String clientName,
            String scope);

    /**
     * Refreshes tokens, replying as {@link #oauthRequestAccessToken} does.
     *
     * <p>An empty {@code scope} keeps the server default, an empty secret is
     * none.
     */
    static native String oauthRefreshAccessToken(
            Transport transport,
            String tokenEndpoint,
            String clientId,
            String clientSecret,
            String refreshToken,
            String scope);

    /**
     * What an account base URL tells of its backend; pure.
     *
     * <p>Returns the {@link BackendInfo} facts, keys in its component order.
     */
    static native String accountInfo(String baseUrl);

    /**
     * Builds an account base URL of a kind, returning {@code {"url": ".."}}.
     *
     * <p>{@code googleCarddav}, {@code google}, {@code msgraph} from an email,
     * {@code jmap} from an HTTPS session URL or bare host.
     */
    static native String accountBase(String kind, String value);

    /**
     * The capability URNs a fresh JMAP session serves, a JSON array.
     *
     * <p>Among mail, submission, contacts and calendars.
     */
    static native String jmapCapabilities(Transport transport, String account);

    /** Lists the account's addressbooks, absolute URLs, as a JSON array. */
    static native String listAddressbooks(Transport transport, String account);

    /**
     * The canonical pimdir SQL, statements keyed by constant, a JSON object.
     *
     * <p>{@link PimdirSql} says why they come from the crate.
     */
    static native String pimdirSql();

    /** The schema version the compiled-in pimdir SQL is. */
    static native int pimdirVersion();

    /** Turns the bridge's debug logs on or off; off keeps warnings and up. */
    static native void debugLogs(boolean on);

    /**
     * Every canonical migration in order, a JSON array of scripts.
     *
     * <p>Applied above the store's {@code user_version} (STORAGE §6).
     */
    static native String pimdirMigrations();

    /**
     * What STORAGE Annex A derives from one body of a kind (its media type).
     *
     * <p>Returns {@code {linkId, summary, sortKey}}, the summary null where
     * the body yields none.
     */
    static native String pimdirDerive(String kind, byte[] body);

    /**
     * The account's mailboxes, a JSON array of {@code {name, role}}.
     *
     * <p>The role is {@code trash} for the RFC 6154 attribute, else empty: an
     * offline delete decides by it between a move and a marker.
     */
    static native String listMailboxes(Transport transport, long session);

    /**
     * One page of a mailbox's listing, the engine's enumerate (SYNC 4, 5).
     *
     * <p>Takes {@code {listing, scope, covered?}}; returns {@code {items,
     * vanished, complete, last, cursor?, checkpoint?}} or a cursorRejected.
     * Graph delta items lack summary and sort key ({@link #nameMessages}).
     */
    static native String enumerateMailbox(
            Transport transport, long session, String mailbox, String request);

    /**
     * Names the messages a Graph delta listed by id and markers alone.
     *
     * <p>{@code handles}: a JSON array of ids. Returns {@code {items}}, each
     * named as a listed one is; a message gone since listed left out.
     */
    static native String nameMessages(
            Transport transport, long session, String mailbox, String handles);

    /**
     * The floor of a mailbox's next chunk: the count-th newest {@code Date}.
     *
     * <p>Below {@code before} (RFC 3339 {@code Z}, empty for none). Returns
     * {@code {floor, dated, dates}}: floor null when fewer lie below, dates
     * newest first.
     */
    static native String mailFloor(
            Transport transport, long session, String mailbox, String before, int count);

    /**
     * Connects and authenticates to the mail server, replying {@code {handle}}.
     *
     * <p>The handle is a pointer: one owner ({@link MailSession}), one thread
     * at a time, freed once with {@link #closeMailSession}.
     */
    static native String openMailSession(Transport transport, String account);

    /** Frees the session a handle names; the sockets are the caller's. */
    static native void closeMailSession(long session);

    /**
     * Has a session work for the pool run numbered {@code run}; 0 leaves it.
     *
     * <p>The run's sessions share a Gmail account's listing, envelopes and
     * history, dropped with the run.
     */
    static native void joinMailRun(long session, long run);

    /**
     * One step: about count messages the store lacks, read into {@code dir}.
     *
     * <p>{@code mailbox} empty for the account, {@code request} a
     * {@link StepRequest}; {@code store} answers {@code {op: "held", handles}}
     * a page at a time. Returns {@link NamedStep} or {@link GraphStepReply}.
     */
    static native String mailStep(
            Transport transport,
            long session,
            String mailbox,
            String request,
            String dir,
            OfflineDriver store);

    /**
     * Reads some messages whole, one file each in {@code dir}.
     *
     * <p>{@code ids}: a JSON array. Returns {@code {items, gone}} as
     * {@link MessageSources}; files, so no message crosses as a string.
     */
    static native String fetchMessageSources(
            Transport transport, long session, String mailbox, String ids, String dir);

    /**
     * Resolves one message's MIME tree into what a reader draws, offline.
     *
     * <p>Returns {@code {subject, from, fromAddress, to, cc, date, kind, body,
     * attachments, attachmentMark}} as {@link MessageBody}.
     */
    static native String parseMessage(byte[] source);

    /** {@link #parseMessage} of a file, read natively: never on the heap. */
    static native String parseMessageFile(String source);

    /**
     * What the store files of the message in the file {@code source}.
     *
     * <p>Returns {@code {hash, size, summary, attachments}} as
     * {@link Description}: {@code sha256-128}, nulls where underivable.
     */
    static native String describeMessage(String source);

    /**
     * Writes one part's decoded bytes, by IMAP section, to {@code target}.
     *
     * <p>Returns {@code {size}}, an error when the message has no such part.
     */
    static native String messagePart(String source, String part, String target);

    /**
     * Adds or removes one marker, named the IMAP way, on one message.
     *
     * <p>{@code \Seen}, {@code \Answered}, {@code \Flagged} on any backend
     * (JMAP keywords, RFC 8621 §4.1.1). Returns {@code {}}.
     */
    static native String setMessageFlag(
            Transport transport,
            long session,
            String mailbox,
            String id,
            String flag,
            boolean add);

    /**
     * Composes one draft, offline, into the RFC 5322 message an outbox holds.
     *
     * <p>Returns {@code {source}}, base64. The bytes keep {@code Bcc} (RFC
     * 5322 §3.6.3), which {@link #submitMessage} takes back out.
     */
    static native String composeMessage(String draft);

    /**
     * Hands one stored message over, to the account's submit URL or the
     * session's API.
     *
     * <p>The envelope comes off the address headers, {@code Bcc} stripped on
     * the way. Returns {@code {messageId?}}, or an error flagged
     * {@code permanent} or {@code uncertain}.
     */
    static native String submitMessage(
            Transport transport, long session, byte[] source);

    /**
     * Moves one message into {@code target}, returning {@code {}}.
     *
     * <p>The push of a removal naming a destination (pimdir SYNC §4).
     */
    static native String relocateMessage(
            Transport transport, long session, String mailbox, String id, String target);

    /**
     * Copies one message into {@code target}, returning {@code {}}.
     *
     * <p>The push of a create naming an origin.
     */
    static native String copyMessage(
            Transport transport, long session, String mailbox, String id, String target);

    /**
     * Deletes one message for good, returning {@code {}}.
     *
     * <p>The push of a removal naming no destination: on IMAP {@code \Deleted}
     * then {@code UID EXPUNGE} with UIDPLUS (RFC 4315), else the marker.
     */
    static native String destroyMessage(
            Transport transport, long session, String mailbox, String id);

    /**
     * Appends one message with {@code flags} (JSON markers); IMAP only.
     *
     * <p>The push of a create naming no origin. Returns {@code {}}.
     */
    static native String appendMessage(
            Transport transport, long session, String mailbox, byte[] source, String flags);

    /** Lists the account's calendars with their URLs, a JSON array. */
    static native String listCalendars(Transport transport, String account);

    /**
     * Enumerates a calendar from a cursor, as {@link EventDelta}.
     *
     * <p>An empty or rejected cursor runs an initial round, flagged
     * {@code complete}, never read as an emptied calendar. The base URL names
     * the backend (a JMAP calendar URL is an id, not an address).
     */
    static native String syncEvents(
            Transport transport, String account, String url, String cursor);

    /**
     * The iCalendar text of the named events, in one round.
     *
     * <p>{@code ids}: a JSON array. Returns {@code [{id, etag, ical}]}.
     */
    static native String multigetEvents(
            Transport transport, String account, String url, String ids);

    /**
     * The occurrences one calendar object has in a civil window.
     *
     * <p>Returns an array of {@link Occurrence}, each time a
     * {@code {time, kind, tzid, offset}}.
     */
    static native String expandEvent(String ical, String windowStart, String windowEnd);

    /**
     * One calendar object read whole, as {@link EventDetail}.
     *
     * <p>The series, or the override a non-empty {@code recurrenceId} names.
     */
    static native String readEvent(String ical, String recurrenceId);

    /**
     * Applies one edit to a calendar object through its syntax tree.
     *
     * <p>Returns the object itself, or a JSON error object.
     */
    static native String writeEvent(String ical, String edit);

    /**
     * Splits a series at the occurrence an edit names.
     *
     * <p>Returns {@code {master, series}}, series null when the occurrence
     * was the first.
     */
    static native String splitEvent(String ical, String edit);

    /**
     * Removes the occurrences an edit's scope names from a series.
     *
     * <p>Returns the object left, empty when none, or a JSON error object.
     */
    static native String removeEvent(String ical, String edit);

    /**
     * Three-way merges a conflicted calendar object, an empty base for none.
     *
     * <p>Returns {@code {ical, resolved, conflicts}}.
     */
    static native String mergeEvent(String base, String local, String remote);

    /**
     * Resolves a conflicted calendar object with {@code picks}' sides.
     *
     * <p>{@code picks}: side by conflict id. Returns the object itself, or a
     * JSON error object.
     */
    static native String resolveEvent(String base, String local, String remote, String picks);

    /**
     * One calendar object as the phone's provider carries it.
     *
     * <p>docs/calendar-mapping.md; {@code now} places the window an unshowable
     * series is listed over. Returns {@code {uid, master, overrides, listed}}.
     */
    static native String projectEvent(String ical, String now);

    /**
     * Patches the phone's edited view onto a calendar object, changes only.
     *
     * <p>An empty object becomes a new one. Returns the object itself, or a
     * JSON error object.
     */
    static native String applyEvent(String ical, String edit);

    /**
     * Pushes an edited object back to its calendar, guarded by any ETag.
     *
     * <p>The account's address makes Google and JMAP announce a meeting it
     * organizes. Returns the new ETag as a JSON string, or null.
     */
    static native String updateEvent(
            Transport transport,
            String account,
            String calendarUrl,
            String id,
            String ical,
            String etag);

    /**
     * The object a new entry starts from, with RFC 5545's required properties.
     *
     * <p>Its start in {@code tzid} with {@code vtimezone} (none for a date or
     * floating). Returns the object itself, or a JSON error object.
     */
    static native String newEvent(
            String component,
            String uid,
            String stamp,
            String start,
            String tzid,
            String vtimezone);

    /**
     * Files a new object in a calendar, guarded on its absence.
     *
     * <p>Announced as {@link #updateEvent} is. Returns {@code {id, etag}}.
     */
    static native String createEvent(
            Transport transport,
            String account,
            String calendarUrl,
            String id,
            String ical);

    /**
     * Removes one object from its calendar, guarded by any ETag.
     *
     * <p>Announced as {@link #updateEvent} is. Returns {@code {}}.
     */
    static native String deleteEvent(
            Transport transport,
            String account,
            String calendarUrl,
            String id,
            String etag);

    /**
     * Lists an addressbook's cards (CardDAV, Graph).
     *
     * <p>Returns an array of {@code {id, uri, etag, vcard}}.
     */
    static native String listCards(Transport transport, String account, String addressbookUrl);

    /**
     * Creates the card in the addressbook.
     *
     * <p>Returns {@code {id, uri, etag, vcard}}, the id the server's where it
     * names its own resources.
     */
    static native String createCard(
            Transport transport,
            String account,
            String addressbookUrl,
            String id,
            String vcard);

    /**
     * Reads the card at a resource name as the server returned it.
     *
     * <p>Returns {@code {id, uri, etag, vcard}}.
     */
    static native String readCard(
            Transport transport, String account, String addressbookUrl, String uri);

    /**
     * Updates the card at a resource name, empty for the id.
     *
     * <p>The base vCard trims patching backends' updates, the ETag guards
     * guarding ones. Returns {@code {id, uri, etag, vcard}}.
     */
    static native String updateCard(
            Transport transport,
            String account,
            String addressbookUrl,
            String id,
            String uri,
            String vcard,
            String baseVcard,
            String etag);

    /**
     * Deletes the card at a resource name, empty for the id.
     *
     * <p>The ETag guards guarding backends. Returns {@code {}}.
     */
    static native String deleteCard(
            Transport transport,
            String account,
            String addressbookUrl,
            String id,
            String uri,
            String etag);

    /**
     * Creates a batch of cards (a JSON array of vCards); Google only.
     *
     * <p>Returns an array of {@code {id, uri, etag, vcard}} in input order.
     */
    static native String createCards(Transport transport, String account, String vcards);

    /** Deletes a batch of cards (a JSON array of ids); Google only. */
    static native String deleteCards(Transport transport, String account, String ids);

    /**
     * Pushes a round of {@link PushChange}s as batch calls; JMAP, Graph only.
     *
     * <p>Returns an array of {@code {ref, accepted, id?, etag?, error?}}, one
     * per change.
     */
    static native String pushCards(
            Transport transport, String account, String addressbookUrl, String changes);

    /**
     * Lists a collection's changes since a cursor, as {@link CardDelta}.
     *
     * <p>Empty or expired runs an initial round; a refused initial CardDAV
     * sync falls back to plain enumeration. Items {@code {id, uri, etag,
     * vcard?, books?}}.
     */
    static native String syncCards(
            Transport transport, String account, String addressbookUrl, String syncToken);

    /**
     * Multigets cards by resource name (a JSON array); CardDAV only.
     *
     * <p>Returns an array of {@code {id, uri, etag, vcard}}.
     */
    static native String multigetCards(
            Transport transport, String account, String addressbookUrl, String uris);

    /**
     * Reads Graph contacts by id (a JSON array), 20 to a {@code $batch}.
     *
     * <p>An unserved request is resent alone, a gone contact left out.
     * Returns an array of {@code {id, uri, etag, vcard}}.
     */
    static native String readGraphCards(Transport transport, String account, String ids);

    /**
     * Reads People contacts by id (a JSON array), 200 to a batchGet.
     *
     * <p>A gone contact is left out. Returns an array of
     * {@code {id, uri, etag, vcard, books}}.
     */
    static native String readGoogleCards(Transport transport, String account, String ids);

    /**
     * Adds and removes book memberships (JSON id arrays), account-level only.
     *
     * <p>Returns {@code {}}.
     */
    static native String updateCardBooks(
            Transport transport,
            String account,
            String id,
            String add,
            String remove);

    /**
     * Syncs a collection through the io-pimdir engine, as {@link SyncReport}.
     *
     * <p>{@code full} ignores the checkpoint; no {@code content}, no body
     * pushed. {@code since} bounds mail by {@code Date} (empty for none);
     * {@code scopeBound}: the checkpoint is bound to its scope.
     */
    static native String offlineSync(
            OfflineDriver driver,
            String collection,
            boolean full,
            boolean content,
            String since,
            boolean scopeBound);

    /**
     * Raises handles (a JSON array) to the full tier, or meta without full.
     *
     * <p>Returns {@code {upgraded, fetched, deduped}}.
     */
    static native String offlineUpgrade(
            OfflineDriver driver, String collection, String handles, boolean full);

    /**
     * Stages a local {@link Mutation}; the remote is never touched.
     *
     * <p>Returns {@code {}}.
     */
    static native String offlineMutate(OfflineDriver driver, String collection, String mutation);

    /**
     * Whether a 412-rejected push may retry unguarded (CardDAV If-Match).
     *
     * <p>Takes {@link RetryFacts}, returns {@code {"retry": bool}}.
     */
    static native String offlineRetryUnguarded(String facts);

    /**
     * Projects an account-wide delta (JMAP, Google) onto one book.
     *
     * <p>Takes {@link AccountSnapshotFacts}, returns
     * {@code {"members": [index], "vanished": [handle]}}.
     */
    static native String offlineAccountSnapshot(String facts);

    /**
     * Plans one push change: membership patch, create or delete.
     *
     * <p>Takes {@link PushPlanFacts}, returns
     * {@code {action, postCreateBooks?}} (Google's post-create membership).
     */
    static native String offlinePushPlan(String facts);

    /**
     * Indexes a vCard for the store, as {@link CardIndex}.
     *
     * <p>Returns {@code {name, email, emails, phone, info, uid, hash}}, plus
     * sortKey and summary.
     */
    static native String indexCard(String vcard);

    /**
     * Merges several vCards (a JSON array) into one union.
     *
     * <p>Returns {@code {vcard, model, alternatives, changed}}.
     */
    static native String mergeCards(String cards);

    /**
     * Three-way merges a conflicted push, local winning collisions.
     *
     * <p>Returns {@code {vcard, conflicts}}.
     */
    static native String mergeCardChanges(String base, String local, String remote);

    /**
     * Builds the conflict form's inputs, the newer side by REV prefilled.
     *
     * <p>Returns {@code {vcard, model, alternatives, changed}}.
     */
    static native String mergeConflictForm(String base, String local, String remote);

    /** Rewrites the card's UID, returning {@code {"vcard": ".."}}. */
    static native String setCardUid(String vcard, String uid);

    /**
     * Finds groups of likely-duplicate cards among {@code {ref, vcard}} pairs.
     *
     * <p>Returns {@code {"groups": [{"refs": [...], "reasons": [...]}]}}.
     */
    static native String findDuplicates(String cards);

    /**
     * The ordered TYPE sets the edit form's spinners address for a kind.
     *
     * <p>The order the Android string-arrays mirror. Returns
     * {@code {"order": [[..], ..]}}.
     */
    static native String cardTypeOrder(String kind);

    /** Projects a vCard onto the neutral {@link ContactFields} model. */
    static native String projectCard(String vcard);

    /**
     * Patches an edited field model onto the vCard, other properties kept.
     *
     * <p>Returns {@code {"vcard": ".."}}.
     */
    static native String applyCard(String vcard, String model);

    /**
     * The edit form's view support of a field model, as {@link FormView}.
     *
     * <p>Returns {@code {name, organization, gender?, birthday?, anniversary?,
     * phones, emails, relations, addresses}}.
     */
    static native String formView(String model);

    /**
     * One typed entry saved from an edit dialog, TYPE set from the spinner.
     *
     * <p>{@code phone}, {@code email}, {@code relation}: the full entry;
     * {@code address}: the TYPE set; {@code gender}: the GENDER, empty unset.
     */
    static native String formEntry(String kind, int index, String value, boolean pref);

    /**
     * One picked date on the model wire, {@code yyyy-mm-dd}, 1-based month.
     *
     * <p>Returns {@code {"value": ".."}}.
     */
    static native String formDate(int year, int month, int day);

    /**
     * Groups a {@link ReplicaPool} into merged contacts, by display name.
     *
     * <p>Returns {@code {"groups": [{key, replicas: [index]}]}}.
     */
    static native String groupContacts(String input);

    /**
     * The duplicate review's group facts for {@code [{ref, book}]}.
     *
     * <p>Returns {@code {key, linkable}}, the dismissal key and Link's offer.
     */
    static native String duplicateGroup(String members);
}
