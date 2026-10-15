---
cairn: change
id: jni-boundary
part: analysis
---

# JNI boundary: analysis

Read-only analysis, 2026-10-15. Paths: J = android/app/src/main/java/org/pimalaya/, C = android/client/src/main/java/org/pimalaya/client/, R = rust/src/. 

## 0. Inputs collected

Every "Phase 8" mention in cairn/changes/archive/java-domain-layout/tasks.md:

| line | item |
|---|---|
| 55 | PimdirStorage split deferred to phase 8 |
| 61 | offline-driver wire and CalendarMapping stay JSON until phase 8 |
| 75 | "Phase 8 candidates (separate change)" heading, body empty (the items live in the notes below) |
| 109 | SyncPasses.collectionsOf filters subscribed books in Java |
| 118 | DeletedItemsPage.appends (backend capability), AccountSettings.serverHost/protocolOf (scheme classification) |
| 120 | DeletedItemsPage parses retainedAt (store format in the UI) |
| 123 | drop the five advanced-editor natives and rust/src/contact/props.rs exports |
| 124 | CalendarRemote.projected sniffs printed JSON for floating times, CalendarRemote.zones walks it for TZIDs |
| 125 | EventView.edited computes the minimal edit in Java (diff, zone attach) |
| 126 | push batches assembled in Java from store rows + pushPlan per change; unknown book id reaches Rust as [null] |
| 127 | PhoneRemote.read crosses the bridge four times per phone contact; Birthdays projects every replica for BDAY |
| 128 | dot-path form keys mapped back in Java, row presence checks, gender and address assembly, ContactWriter date regex |
| 129 | phone fetched items carry no summary/sortKey; DuplicateReview's first-open-group search |
| 131 | MailSubmit.stageSentCopy builds the sent copy's summary in Java |
| 132 | resource derivation, discovered option ranking, scheme choice in Endpoints, scope arithmetic, Java-driven renewal |
| 133 | B11 JSON exceptions: enumerateMailbox/nameMessages (decision 1), projectEvent/applyEvent (decision 2), Flags.read(JSONArray), JSONObject `summary` of Derived/Description/CardIndex |
| 134 | MailStore.query pairs hidden sent copies from queue payloads; releaseCrossLinked hand-writes SQL; delete semantics from policy in Java |
| 135 | PimdirStorage and CalendarMapping kept wide (phase 8); SUSPECTED BUG: loopback OAuth grant receives the RFC 8707 resource and never sends it |

Phase 5 orchestrator decisions (phase5-design.md:509-517): 1. offline-driver wire stays JSON until phase 8, enumerateMailbox/nameMessages recorded exceptions. 2. CalendarMapping and the PhoneEvent view deferred to phase 8, projectEvent/applyEvent stay JSON, step B3b not done. phase5-design.md "F. Phase 8 candidates" (11 items) and phase4-design.md "E" (7 items) add: engine page naming in Java, ContactEngine facts round trips (3 JNI calls per decision), whole-flag-set read-modify-write, searchAll fan-out, ServerMetadata scopes join/re-split, text-reply error sniffing (`{` prefix), GmailStep mailbox distribution, ContactPool feeding Rust from store rows, dead `discover`/`listAccountCards`, setup planning, reply/forward composition, event write branching, body policy by role, store stamp formats.

Spec constraint to know before Q3: cairn/spec/offline-store.md:9 states the crate is taken "without its `client` feature: compiling a second engine into every ABI would work against the app's first design goal". Moving storage to Rust reverses a recorded spec decision.

## 1. The positional account tuple

**Where.** C/Native.java declares 100 natives (R/ffi: account 19, calendar 16, contact 35, mail 20, offline 10 exports). 21 take `login, password` (Native.java lines 194-725):

- with `baseUrl, login, password`: jmapCapabilities, listAddressbooks, listCalendars, listAccountCards, createCards, deleteCards, updateCardBooks (account only); syncEvents, multigetEvents, updateEvent, createEvent, deleteEvent (+ calendar url, and `address` on the three writes); listCards, createCard, readCard, updateCard, deleteCard, pushCards, syncCards (+ addressbook url).
- variants: openMailSession(url, login, password) (the mail session holds them afterwards, R/mail/session.rs:117-165); multigetCards(addressbookUrl, login, password) with no base URL.
- token-only: readGraphCards, readGoogleCards (`token`), 2 more.

**Components.** `Transport` (separate, a socket owner), `baseUrl` (DAV context root or sentinel `msgraph://`, `google://`, `jmap://`, `local://`, R/account.rs:16-36), `login` (empty means Bearer), `password` (password or access token), plus per-call `address` (the account's own email, calendar writes only) and, on the Java record, `submitUrl` (C/Account.java, 4 fields, mail only).

**Rust decode.** Each export reads three JStrings and calls `Account::new(base_url, login, password)` (R/account.rs:141), which sets `backend = Backend::of(base_url)` (prefix match, R/account.rs:60) and `Auth::new` (empty login gives Bearer, R/client.rs:57). 20 sites in R/ffi (contact 14, calendar 6) plus `MailSession::open`.

**Java build.** `AccountEntry.server(PimDomain)` (J/account/AccountEntry.java:95-103) is the one production builder (`new Account(connection.baseUrl, credential.login, credential.secret, connection.submitUrl)`), plus 3 OnboardingFlow probes (1810, 2038, 2142). The domain clients splat it: `account.baseUrl/login/password` read 36 times in ContactClient, 16 in CalendarClient, 3 in MailClient. Token renewal is Java-driven: a 401 (`PimalayaException.status`, J/pimdir/PimdirEngine.java:356-359) runs `TokenRenewal.renew` (J/account/TokenRenewal.java) and the next call carries the new secret.

**Options.**

| | A. one JSON `account` string | B. Rust-held handle (`long`) | C. Java object read by JNI field access |
|---|---|---|---|
| shape | `{baseUrl, login, secret, address?, submitUrl?}` parsed to `AccountJson` then `Account` | `openAccount(json) -> handle`, verbs take the handle, `closeAccount` | `Account` passed as jobject, Rust reads fields |
| renewal | free: next call carries the new secret | handle must be updated (`setSecret`) while workers may be mid-call on it: needs a lock or reopen | free |
| lifetime | none | ownership, close, leak on a dropped worker; shared across WorkerPool threads (needs `Arc<Mutex>` or one per worker) | none |
| state it could hold | none (but JMAP sessions are already cached process-wide, R/client/jmap.rs:74-80, 245) | backend, auth, caches | none |
| cost | one small serde parse per call | none per call | reflective JNI reads, R8 keep rules on Account fields |
| precedent | the rest of the wire | MailSession (R/ffi/mail.rs:49-90) | none in the tree |

**Recommendation: A.** One `String account` argument, decoded once by a shared `parse_account` helper in R/ffi.rs into an owned `AccountJson` (secret in `secrecy::SecretString`, already a dependency, so no Debug leak), from which `Account` borrows. Removes 2 params from 21 natives and 20 copy-pasted decode blocks; `address` folds into the record (it is the account's email), so `updateEvent` goes from 9 to 7 params. B buys nothing today: the only cache worth holding (JMAP session) already lives in Rust, and renewal would become a concurrency problem. Keep `Transport` separate (it is per worker, not per account). Fold `multigetCards` onto the same record (it ignores baseUrl today) and `readGraphCards`/`readGoogleCards` onto it too (token = secret), so every network verb has one account shape.

Also send `BackendInfo` back once: `Account` gains its backend facts at construction (one `accountInfo` call per AccountEntry.server, or Java keeps the memo map but keyed per Account), which kills the static `ConcurrentHashMap` memo in C/AccountClient.java:321-328 (a java-009 global).

## 2. Backend questions Java answers itself

38 `AccountClient.is*/writes*/submits*` call sites in 14 :app files. Rust answers all of them from `Backend` (R/account.rs:47-120) but Java re-asks and branches. Findings, with the Rust owner each should move to:

| where | decides | Rust owner |
|---|---|---|
| C/AccountClient.java:262-328 (isAccountLevel, isCarddav, isGraph, isGoogle, isJmap, writes*, submitsOverSession) | backend predicates, memoized globally | `Backend`; Java keeps only what a screen needs (writes, read-only) as typed facts on Account |
| R/account.rs:83-108 `writes_overrides`/`writes_exdates`/`writes_events` | all return true for every remote backend; only `local://` answers false (R/ffi/account.rs:638-656) | delete the three: they are "is not local"; EventView.java:339, 577, 1498, 1623 then branch on nothing |
| J/mail/MailSubmit.java:143-145 `marksAnswered` (`!Graph && !Google`), used MessageCompose.java:234 | whether a reply stages `\Answered` | `Backend::keeps_answered()` or Rust refusing/ignoring the marker in the step |
| J/trash/DeletedItemsPage.java:191, 256-266 `appends` (`!Graph && !Google && !Jmap`) | whether a restore can re-upload mail | `Backend::appends_mail()` (IMAP only); appendMessage is IMAP-only already (Native.java:398) |
| J/trash/DeletedItemsPage.java:451-452 | parses `retainedAt` by cutting to 19 chars and appending Z | the store format: DeletedItems.Row should carry a long; with Q3 it is Rust's |
| J/mail/MailEngine.java:96-102 `sessionsOf` (Graph 4, JMAP 4, Gmail 2, IMAP 3) | worker pool width per backend, used SyncPasses.java:651, SyncCoordinator.java:815, 997 | `Backend::sessions()`: R/client/throttle.rs already owns Gmail pacing |
| J/mail/MailEngine.java:145-147 | account-wide listing (Google, JMAP), All Mail (Google) | `Backend::account_wide()`, `has_all_mail()` |
| J/mail/MailStep.java:63-66 | GraphStep vs ImapStep vs account step | Rust step dispatch (mailStep already returns NamedStep or GraphStepReply by backend) |
| J/sync/SyncPasses.java:727, 801 | Gmail load-more steps by label + All Mail | same `account_wide`/`has_all_mail` facts |
| J/mail/MessageView.java:704 | JMAP with no trash refuses delete | `Backend` delete semantics; belongs with "delete semantics decided from policy in Java" (tasks 134) |
| J/contact/ContactEngine.java:334, 356, 444-455, 616-623, 1141, 1192-1203 | fetch strategy (Google batchGet, account-level deltas, Graph $batch, CardDAV multiget), push strategy (Google batch, JMAP/Graph round, CardDAV parallel), CardDAV post-write re-read when no ETag | one Rust `fetchCards`/`pushCards` verb per backend choosing the batching; Java keeps only the parallel transports |
| J/contact/CardStore.java:455-467 `resourceName` (`id.vcf` on CardDAV, else id) | create resource name | `account::addressing_key` (R/account.rs:215-224) already exists but disagrees on Graph (Rust: `id.vcf`, Java: bare id); different purposes (fallback key vs create name), check before unifying |
| J/contact/ContactPool.java:99, ContactWriter.java:204, 239 | per-account vs per-book card keys | `Backend::account_level` (stays a fact on Account) |
| J/setup/AccountSettings.java:318, 697-722 `serverHost`/`protocolOf` | display host and protocol label by sentinel prefix | a Rust `describe(baseUrl) -> {host, protocol}`; labels stay Java strings keyed by an enum |
| J/account/Endpoints.java:38-129, 147-189 | manual URL (imaps default 993), submission URL (smtp/smtps by security), endpointUrl (service to base URL, https-only rule), `host()` excluding sentinels, `destination()` mapping jmap to https | Rust onboarding: io-pim-discovery's ServiceConfig already carries service/security/host/port; one `endpointOf(config, email)` |
| C/AccountClient.java:218-246 `submitUrl`, `withUser` | port to scheme rule (587/25 STARTTLS, else TLS, default 465), userinfo for XOAUTH2 | logic inside :client Java, not a bridge call: move into Rust with the above |
| J/setup/OnboardingFlow.java:2039, 2213 | JMAP probes capabilities first | onboarding plan (phase4 E.1) |
| J/account/Oauth.java:120-175 `isGoogle(endpoint)`, `shipped`, `fixedScope`, `audience` | which shipped client, scope intersection, token audience | io-oauth / Rust onboarding (tasks 132 "scope arithmetic") |
| J/sync/SyncPasses.java:965-975 `collectionsOf` -> CardStore.subscribedBooks (CardStore.java:365-377) | contacts collections filtered by the app's `addressbook.subscribed` switch | app-only: the switch lives in CardStore's own SQLite (`addressbook` table, CardStore.java:57), not in pimdir. Stays Java unless the subscription moves into pimdir collections; not a Rust question |
| J/calendar/EventView.java:1400-1431 `edited` + `beyondStamp` 1438-1452 | minimal edit (field diff, zone change detection, VTIMEZONE attach) | Rust `editOf(opened page, form state)`, it already owns `EventEdit` application; the VTIMEZONE needs a tz source (see Q4) |

## 3. Storage

**What Java holds.** J/pimdir is 6,331 lines; SQL call sites: PimdirStorage 84, PimdirItems 39, PimdirDb 34, CardStore 33, MailStore 21, PimdirContacts 19, PimdirCollections 9, PimdirQueue 7. PimdirStorage (1,941 lines, 78 JSON tokens) serves the engine's `load`/`lookup`/`write` yields (J/pimdir/Stager.java:127-147) on requery SQLite 3.49 (android/app/build.gradle.kts:190).

**Duplicates of io-pimdir** (io-pimdir 0.8 `client` feature, about 7,400 lines over rusqlite: R deps take it only in dev-dependencies, rust/Cargo.toml:22-26):

| Java | io-pimdir |
|---|---|
| PimdirStorage.loadCollection 124-335 (scope clause, sync state, placements) | `PimdirSourceStore::load` (src/client/write.rs:62) |
| PimdirStorage.lookupObjects 524-566 | `lookup_objects` (write.rs:132) |
| PimdirStorage.applyWrites 568-1115 (drops, stamps, checkpoint, refcounts 1867, GC 1882) | `write` (write.rs:163), `collect_garbage`, `recompute_refcounts` (src/client.rs:661-705) |
| PimdirQueue | producer.rs, `acknowledge_action`/`fail_action`/`replace_action` (client.rs:748-841) |
| DeletedItems retention/purge | `purge`, `purge_retained_before`, `release_before` (client.rs:515-619) |
| PimdirDb schema + migrations (schema from `pimdirSql`/`pimdirMigrations` natives) | schema.rs |
| MailStore.releaseCrossLinked (hand-written SQL, MailStore.java ~1499-1515) | `release_before`'s predicate (cited in imap-step-fixes proposal) |
| PimdirBlobs, PimdirHash | blobs.rs, hash.rs |

**App-only** (no io-pimdir counterpart): phone spoke naming (`phone:` prefix, PimdirStorage.java:64-90), provisional handles and pending-create lookups by Message-ID (1463-1650, used by mail naming), Gmail All Mail and sent-copy pairing (MailStore.query), CardStore's `addressbook`/`detached`/`link`/`dismissed_duplicate` tables (a separate database), MailPolicy prefs, list queries feeding screens.

**Risks of moving.**
- Two SQLite copies in one process: requery ships its own libsqlite3x.so; rusqlite `vendored` compiles a second one. Two copies opening the same file break POSIX advisory locks (each copy's close drops the other's locks: sqlite.org/howtocorrupt.html section 2.2.1). So the move is all-or-nothing per database file, or Rust must link requery's library instead of vendoring (unverified whether it exports the `sqlite3_*` symbols).
- Size: a second SQLite plus the client code in 4 ABIs, against the spec's stated design goal.
- Format: the store is already io-pimdir's schema, so no data migration; but the app's additions (indexes, roles backfill, PimdirDb.reconcileRoles) must match what `PimdirStore::open` expects, or it refuses or migrates on open.
- Perf: StorePerformanceTest pins today's paths; Rust-side load/write removes the JSON wire for load/write yields (the largest payloads), likely a win, but list screens would cross JNI per query unless reads stay in Java.

**Recommendation: later, as its own change, not in phase 8's first steps.** Phase 8 types and narrows the boundary; storage is a reversal of offline-store.md:9 that needs the user's decision and a spike (does `PimdirStore` open the device store unchanged; can rusqlite link requery's library). Now: stop growing it (no new hand SQL; route releaseCrossLinked through a canonical statement), and do the cheap app-only fixes (retainedAt as long). If the decision is yes, the target is "Rust owns writes and the engine seam, Java keeps read-only list queries on the same library copy", which kills the load/lookup/write yields and most of Q5's wire.

## 4. CalendarMapping / PhoneEvent

**Size.** J/calendar: CalendarMapping 1,677 lines (103 JSON tokens), CalendarRemote 1,284 (40), Zones 588. Rust R/calendar/phone.rs 2,721 lines already owns object to view (`project`, `apply`, typed `PhoneEvent`, `PhoneTime`, `PhoneAlarm`, `PhoneAttendee`, `PhoneComponent`, `PhoneEdit`, phone.rs:72-209); only the Java side treats them as JSONObject (C/CalendarClient.java:290, 301).

**What the Java does** (docs/calendar-mapping.md:8-12 draws the split: rows to view in Java, view to object in Rust): view to provider rows (`rows` 118-306: columns, clipping, status/access/attendee codes), RRULE/RDATE/EXDATE cells and UNTIL coercion (`coerced`/`until` 1276-1316), instance alignment (319-357), field-space merge of the phone's edits (`merge` 619, `reverts` 725, `times`, `series`, alarms, attendees 750-1016), the write plan against existing rows (`plan` 1398), fingerprints (1532). Zones: Windows to IANA table (Zones.java ~40-185), TZID resolution (208-258), VTIMEZONE synthesis from java.time rules (422-575).

**Does ical-rs do it?** Partly. ical-rs 0.6 has recurrence sets (used by phone.rs) and an opt-in `tzdb` feature with `tzdb::vtimezone(tzid, anchor)` (~/code/pimalaya/ical/src/tzdb.rs:76), bundled through jiff. It is already compiled into libpimalaya.so: io-gcal and io-msgraph enable `ical-rs/tzdb` (their Cargo.toml:42/40) and jiff-tzdb is in rust/Cargo.lock:812. The Windows table also exists in io-msgraph (src/v1/rest/users/events/ical/windows_zones.rs:154, private). Nothing maps to CalendarContract rows: that half is Android's.

**Recommendation.**
1. Now: type the view (Java records for the six Rust structs) and drop decision 2's JSON exception; CalendarMapping keeps its logic, its 103 JSON tokens become record accessors. Capture-before-change: rows and merges on a corpus (MappingCorpusTest is the contact precedent: 878 outcomes).
2. Next: replace Zones.vtimezone with ical-rs `tzdb::vtimezone` (a new pure native), and ask io-msgraph to expose `windows_zones::iana`. Trade-off: bundled tzdb vs device tzdata may disagree on recent rule changes; the provider uses device tzdata. Pin on a captured corpus; accept differing bytes only where the zone era differs.
3. Later or never: moving rows and merge to Rust. Merge compares civil times in the device zone (calendar-mapping.md:213), so it needs device tzdata semantics; Rust would have to use the bundled one or receive pre-resolved offsets. Large, risky, and the result stays Android-specific. Not worth it unless a second phone platform appears.

## 5. Offline-driver wire

**Today.** `OfflineDriver.serve(String yieldJson) -> String` (C/OfflineDriver.java); yields `load, lookup, write, enumerate, fetch, push` plus mail's `held` (R/offline/wire.rs:947-974, typed in Rust; J/pimdir/Stager.java:127-147, J/pimdir/PimdirEngine.java:123-146). The Rust side is all serde (wire.rs 1,165 lines: PlacementJson, WriteOpJson, ChangeJson, MutationJson, SnapshotJson, FetchedItemJson...). Java edits the JSON in place: PimdirEngine.named/keepBoundNames (179-260) strip `summary/sortKey/hash/body` and set `linkId` from `offline.bound`; MailEngine.enumerate (513-535) builds the request, then nameUnnamed (618-672), steppedIn (677-711), nameByMessageId (729-775) rewrite items; landed (538-551) fabricates a page; listed (559-590) owes arrivals.

**What typing takes.**
- Pure typing: Java records for EnumerateRequest, EnumeratedPage, RemoteItem (handle, linkId, revision, summary, sortKey, hash, body/bodyBase64), FetchedItem, PushResult, plus the load/write family if PimdirStorage stays (PlacementJson, WriteOpJson's tagged variants, ~10 ops). Roughly 15-20 records; `summary` stays a JSONObject (persistence format bound by column name, B11).
- Better: move naming into Rust. Every naming decision reads `offline.bound(collection, handles)` (a store read) then edits items. A `bound` yield (Rust asks, Java answers `{handle: [linkId, revision]}`) and a `pendingCreates` yield let Rust's enumerate driver name members itself (phase5 F.1); then enumerateMailbox and nameMessages stop crossing as JSON at all, and MailEngine loses ~250 lines. Gmail's steppedIn needs `heldOf(accountCollections)` and the `sawHeld` round state: one more yield, state kept on the Rust MailSession.
- Gate: capture every yield/reply pair on the existing engine tests (MailEngineTest, BodyLossRacesTest, StorePerformanceTest, the step e2e fixtures) before, compare after, byte-equal as JSON values.

## 6. Dead natives

No Java caller left for the five advanced-editor natives; only Native.java declarations and the stale R8 outputs in android/app/build/outputs/mapping (build artifacts).

Remove:
- Java: Native.java:824 `cardProps`, 831 `cardSetProp`, 839 `cardSetPropParts`, 847 `cardPropLabels`, 862 `cardSource`.
- Rust exports (R/ffi/contact.rs): `Java_org_pimalaya_client_Native_cardProps` (913), `_cardSetProp` (936), `_cardSetPropParts` (962), `_cardPropLabels` (991), `_cardSource` (1031), and the `props::{...}` import (ffi/contact.rs:31-33).
- The whole module R/contact/props.rs (409 lines, nothing else imports it), `pub mod props;` (R/contact.rs:20) and its mention in the module doc (contact.rs:8).

`cardTypeOrder`: still present at the time of reading (Native.java:855, ContactClient.java:350, R/ffi/contact.rs:1012, R/contact/form.rs:224 `card_type_order`). Its only callers are tests: TypeTablesTest.java:88 and ContactIndexTest.java:100-104. It is not dead weight: TypeTablesTest pins arrays.xml spinner positions to Rust's TYPE sets (a mismatch once saved Work as Home). Deleting it drops that cross-language check unless the spinner tables move to Rust or the check is re-expressed. Also dead per phase 5 decision 5: `discover` (R/ffi/account.rs:38) and `listAccountCards` (R/ffi/contact.rs:121) have no Java caller beyond Native.java.

## 7. Other backend/protocol logic in Java

- Setup planning: J/setup/SetupPlan.java (315 lines: option ranking 39-135, auth ranking, sign-in pooling 206-259, scope union 266, resource derivation 284) and OnboardingFlow's commit assembly.
- Discovery fan-out: C/AccountClient.searchAll (38-120) runs six searches, merges via searchMerge, skips probes by reading raw JSON.
- Mail composition: J/mail/MailReply.java (85 lines: reply-all addressing, own address removal), MailSubmit.stageSentCopy (111) building a summary, MailStore.query pairing hidden sent copies from queue payloads.
- Mail flags: whole-flag-set read-modify-write (MailStager, DeletedItems 309, MailEngine.pushFlags over PimdirStorage.baseFlags 1117); a mutation carrying add/remove of one marker would let Rust apply it.
- GmailStep (151 lines) distributing step items by mailbox names.
- Body policy by role: MailBodies.downloads/takes (84-113), BodyPlan (148 lines).
- Text-reply verbs signal errors by a leading `{` (writeEvent, removeEvent, newEvent, resolveEvent, applyEvent) and updateEvent answers a bare JSON string; Replies sniffs them. Uniform `{ical}`/`{etag}` replies remove it.
- Store stamp formats: DeletedItems.stamp (319), PimdirSummary.stampOf (321), MailDate.
- Contacts: ContactEngine facts round trips (accountSnapshot, pushPlan, listingUnchanged: three crossings per decision), PhoneRemote 4 crossings per phone contact, Birthdays projecting every replica, ContactWriter date regex, form dot-path keys and gender/address assembly.
- Calendar: CalendarRemote.projected/zones walking printed JSON (tasks 124), EventWrites branching (split to add + edit).

## Proposed ordering

Each step is small, one reviewable diff, with its gate. JVM = `:app:testDebugUnitTest` + `:client:testDebugUnitTest`; cargo = `cargo test` in rust/ via nix; capture = record wire values before, assert equal after.

1. **Dead code.** Delete the five natives, their exports and props.rs; `discover`, `listAccountCards` exports. Gate: cargo test, JVM, release build (R8 keep rule is `Native { *; }`, so no rule change). cardTypeOrder per decision D5.
2. **Constant predicates.** Remove `writes_overrides/writes_exdates/writes_events` (always true off local) and their Java branches; EventView asks "local or not". Gate: JVM (EventView save paths), cargo.
3. **One account argument.** `parse_account` + `AccountJson` in R/ffi.rs; 21 natives take `String account`; `address` folded in; domain clients pass `Account.json()`. One family per sub-step (contact, calendar, mail open, token-only). Gate: capture the decoded `Account` per export in a Rust test table; JVM fakes unchanged (they override domain verbs, not Native).
4. **Backend facts on Account.** BackendInfo gains `appendsMail`, `keepsAnswered`, `accountWide`, `hasAllMail`, `sessions`; Account carries it; the static memo goes; the 38 call sites read facts, not backend identity. Gate: JVM, cargo table test of every backend's facts.
5. **Small store-format fixes.** DeletedItems.Row.retainedAt as long; AccountSettings server description from Rust. Gate: JVM (DeletedItemsTest), capture of rendered status strings.
6. **Typed phone event view.** Records for PhoneEvent family; CalendarMapping off JSONObject. Gate: capture rows/merges/plans on a calendar corpus (new CalendarMappingCorpusTest, like MappingCorpusTest), identical.
7. **Uniform text replies.** `{ical}`/`{etag}` for the five text verbs and updateEvent; Replies.text goes. Gate: capture, JVM RepliesTest.
8. **Naming in Rust.** `bound` / `pendingCreates` / `held` yields; enumerate driver names members; MailEngine naming code and enumerateMailbox/nameMessages JSON go. Gate: capture all yields on engine tests and step fixtures; BodyLossRacesTest, StorePerformanceTest.
9. **Remaining wire typing** (fetch/push/load/write records) only if D3 says storage stays Java. Gate: capture.
10. **Contact fetch/push strategy in Rust**, then setup planning and endpoints, then vtimezone via ical-rs (after D4). Each its own step with capture.

## Open decisions for the user

- **D1. Account argument shape.** JSON record (recommended), Rust handle, or JNI field reads. Recommend JSON: stateless, renewal stays trivial, JMAP sessions already cached in Rust.
- **D2. Backend facts vs identity.** Keep `isGraph/isGoogle/isJmap` public for screens, or expose only capability facts. Recommend facts only; AccountSettings' protocol label gets its own enum from Rust.
- **D3. Storage to Rust.** Reverses offline-store.md:9 and risks two SQLite copies in one process. Recommend: not in phase 8; run a spike (PimdirStore on a copy of a device store; rusqlite linked to requery's library) and decide then.
- **D4. Time zones from bundled tzdb.** Use ical-rs `tzdb::vtimezone` (already linked) instead of Zones.vtimezone, accepting bundled-vs-device tzdata differences. Recommend yes, behind a corpus capture, after step 6.
- **D5. cardTypeOrder.** Delete (losing the spinner/Rust alignment test) or keep as a test-only native. Recommend keep until the spinner tables come from Rust (labels by enum), then delete with the test.
- **D6. CalendarMapping logic to Rust.** Recommend never for rows and merge (device tzdata, Android-only); type it only.
- **D7. Offline-driver naming.** Type the wire as is, or move naming into Rust first. Recommend naming in Rust first (step 8): it deletes the JSON instead of typing it twice.
- **D8. OAuth resource bug** (tasks 135): fixed 2026-10-15 (d1f659f), the loopback grant sends the resource; exchange and refresh stay network-trust-hardening L3.
