# Phase 5 design: typed data (java-domain-layout)

Paths: `client/` = android/client/src/main/java/org/pimalaya/client/, `app/` = android/app/src/main/java/org/pimalaya/, `test/` = android/app/src/test/java/org/pimalaya/, `rs/` = rust/src/. Lines are today's tree (after phase 4). `N123` = Native.java line.

## 0. Verdict and challenges (read first)

Size: the largest phase by far. 101 natives, 31 :client files (PimalayaClient 1712), ~1,000 JSON tokens left in :app, ~150 methods with 5+ parameters, 35 test files referencing PimalayaClient (99 refs). Honest estimate: 25-30 editing steps. Split it into four sub-phases with their own gates and device smoke points: **5A** split :client (moves only), **5B** typed replies and Java-built inputs per domain, **5C** mail store (Flags, StoredMessage, Outbox, MailPolicy), **5D** params records for what is left.

Challenges to the brief, each with a recommendation for the user (one AskUserQuestion before 5A):

1. **The offline-driver wire stays JSON until phase 8 (recommended).** `OfflineDriver.serve` yields (rs/offline/wire.rs `YieldJson` 947, `MutationJson` 584, ~1,000 lines of wire types) are answered by PimdirStorage (2104 lines, already deferred to phase 8) and by the engines' enumerate/fetch/push/held replies. The pages are not read, they are reshaped: PimdirEngine.named 243 and keepBoundNames 196, MailEngine.nameUnnamed 683, nameByMessageId 796, steppedIn 742, landed 597, ContactEngine.snapshot 427/fetchedItem 606, CalendarRemote/PhoneRemote enumerate/fetch/push. That is pimdir SYNC section 4 naming done in Java, the exact "JSON reshaping Rust could answer" the user wants gone, and typing it would duplicate offline/wire.rs in Java. Phase 5 only gathers the wire builders in one place per side (below) and records the exception. This covers `MailClient.enumerateMailbox` (rs `MailPage` listing.rs 269) and `nameMessages` (`Named` 333): they stay `JSONObject`/`JSONArray` returns, the one recorded exception to "no public JSON".
2. **CalendarMapping and the PhoneEvent view: defer to phase 8 (recommended).** Plan section 3 already lists CalendarMapping's recurrence/time logic vs rs/calendar/phone.rs as phase 8. It is 1,649 lines with 103 JSON tokens, mutating copies of `PhoneEvent` components (merge 606, component 745, instance 1182, withOverride 1209). Typing it now is churn on code phase 8 may halve. `CalendarClient.projectEvent/applyEvent` keep `JSONObject` (second recorded exception). If the user prefers it now, it is one extra sub-step (5B-cal-phone, below) after B3.
3. **Contact Mapping is typed now.** Its merge (Mapping 319) is a phase 8 candidate, but rows/model are platform mapping that stays, and `ContactFields` is also the edit form's model (ContactForm, ContactFieldDialogs), which stays in Java whatever phase 8 decides.
4. **AdvancedEditor is unreachable** (phase 4 note: `contact_advanced` never visible, `AdvancedEditor.open` has no caller). Typing its six verbs (cardProps, cardSetProp, cardSetPropParts, cardPropLabels, cardSource, projectCard use) is wasted work. Ask the user: delete AdvancedEditor (recommended; natives stay so JNI symbols are unchanged) or type it.
5. **Dead wrappers:** `PimalayaClient.discover` 43 and `listAccountCards` 1313 have no :app caller. Recommend not carrying them into the domain clients (natives stay); user decision, as with phase 4's dead code.
6. **"Fields in the order Rust serializes them"** is right for records mirroring a reply. Inputs Java builds are order-insensitive on the Rust side (serde structs), so their invariant is "equal as JSON values, null distinct from absent", verified by effect (below). Persisted JSON (SecureStore, queue payload, PendingGrantStore, summaries) must keep its bytes and is not re-serialized by records at all.
7. **Pinning on real replies needs more than the brief says.** `:client` tests do not load libpimalaya (only :app's test task sets `java.library.path`, app build.gradle 96-132), and they are not in the gate. Pins live in the :app test tree under package `org.pimalaya.client` (test/client/, reaching package-private `from`), calling the real bridge. Pure verbs pin directly. Network verbs pin over a scripted local HTTP server (Transport takes plain `http`, Transport 245; JDK `com.sun.net.httpserver`) for CardDAV/CalDAV, and a scripted plain-`imap` socket (Transport 246) for the IMAP session verbs. Shapes only a fixed https host produces (Graph/Gmail step items, `Labelled` sources, OAuth token responses if the bridge refuses http) pin on literal fixtures copied from the Rust tests, plus a shape check. Also add `:client:testDebugUnitTest` to the gate.
8. **org.json differs between tests and device.** :app tests use `org.json:json:20240303` (app build.gradle 198), whose JSONObject is HashMap-backed; Android's keeps insertion order. Any byte/order assertion must run on raw strings, never on a re-printed JSONObject. Verify which org.json Robolectric loads (one probe test) before writing order assertions.
9. **Item 4 (setup tests):** AccountCommitTest already exists (5 tests). What phase 4 could not test is `SetupPlan.resourceOf` 237, `Endpoints.submissionUrl` 71/`endpointUrl` 108 and OnboardingFlow.optionsFor 1228 (private, builds from ServiceConfig). Records with public canonical constructors unblock all four (B10).
10. **Keep `Account` as it is.** It is the positional tuple phase 8 replaces; converting it to a record now is 87 accessor edits redone in phase 8.
11. **Static vs instance in the domain clients:** pure computations (no transport, no session) stay static, as Cards/EventViews are; verbs through a Transport or MailSession are instance methods, the fake seam phase 6 uses. Clients stay non-final until phase 6.

## A. Inventory

### A1. Every native (101), reply type, consumer, target

Reply wrapper types in rs/ffi: account.rs UrlReply 694, CodeReply 700, SessionParamsReply 706, ScopeReply 713, ClientInformationReply 719, AccountInfoReply 727; calendar.rs SplitReply 588; contact.rs VcardReply 722, PropsReply 729, LabelsReply 736, TypeOrderReply 743, DateReply 750; mail.rs OpenedReply 725, NamedReply 732, SizeReply 739, SourceReply 745, SubmittedReply 753, RefusedReply 759; offline.rs DerivedReply 292. Error envelope rs/error.rs BridgeError 27. A single-field wrapper is unwrapped to its field in Java (no record), a multi-field one becomes a record.

**account (rs/ffi/account.rs) -> AccountClient**

| Native | Rust | Reply type | Today's Java consumer | Target |
|---|---|---|---|---|
| discover N30 | :38 | UrlReply | PimalayaClient.discover 43, no app caller | dropped (dead, ask) |
| searchProvider N39 | :67 | Vec<io_pim_discovery DiscoveryServiceConfig> | PimalayaClient 58 <- setup/OnboardingFlow | `List<ServiceConfig> searchProvider(Transport, String email, String resolver)` |
| searchPacc N46, searchCarddav N53, searchCaldav N60, searchAutoconfig N67, searchJmap N74 | :91 :109 :128 :148 :172 | same | searchMechanism 148 (internal) | internal raw strings (Rust input to searchMerge) |
| searchMerge N82 | :191 | Vec<DiscoveryServiceConfig> | searchAll 107 | internal raw (each config is searchProbe's input) |
| searchProbe N90 | :211 | DiscoveryServiceConfig | searchAll 124 | internal; `List<ServiceConfig> searchAll(String, String)` parses at the end |
| oauthSessionParams N97 | :380 | SessionParamsReply | OauthSession ctor 31 | package-private `record Pkce(String state, String verifier)` |
| oauthDomainScope N105 | :403 | ScopeReply | ServerMetadata.domainScope | `String` |
| oauthAuthorizeUrl N113 | :307 | UrlReply | OauthSession.authorizeUrl 97 <- setup/OauthFlow 562 | `String authorizeUrl(String endpoint, Map<String,String> extras)` (LinkedHashMap, serialized in insertion order) |
| oauthValidateRedirect N128 | :353 | CodeReply | OauthSession.redeem 126 | internal `String` |
| oauthRequestAccessToken N138 | :427 | io-oauth token response | OauthSession.redeem | `OauthTokens` |
| oauthServerMetadata N154 | :474 | io-oauth AS metadata | PimalayaClient 225 <- OauthFlow | `ServerMetadata` |
| oauthRegisterClient N162 | :506 | ClientInformationReply | PimalayaClient 236 <- OauthFlow | `String registerClient(Transport, ClientRegistration)` (reply parsed to package-private `Registered(clientId, clientSecret)`, secret unused as today) |
| oauthRefreshAccessToken N176 | :544 | token response | PimalayaClient.oauthRefresh 203 <- sync/SyncPasses | `OauthTokens refresh(Transport, OauthGrant grant, String refreshToken)` |
| accountInfo N193 | :627 | AccountInfoReply | info() 392 + 11 static predicates (isGoogle x11, isJmap x8, isAccountLevel x8, isGraph x6...) | `static BackendInfo info(String url)` (memo kept); predicates stay as static forwards (phase 8) |
| accountBase N201 | :670 | UrlReply | googleCarddavBase/googleBase/msgraphBase/jmapBase 252-271 | statics, `String` |
| (Java only) submitUrl 280, withUser 296 | - | - | Endpoints, OnboardingFlow | statics on AccountClient |

**contact (rs/ffi/contact.rs) -> ContactClient**

| Native | Rust | Reply type | Consumer today | Target |
|---|---|---|---|---|
| jmapCapabilities N208 | :48 | Vec<String> | PimalayaClient 860 <- OnboardingFlow | `Set<String> jmapCapabilities(Transport, Account)` (account concern, kept with its Rust file) |
| listAddressbooks N216 | :87 | Vec<Addressbook> rs/contact.rs:45 | 402 <- OnboardingFlow, SyncPasses | `List<Addressbook>` |
| listAccountCards N648 | :121 | Vec<Card> | 1313, no app caller | dropped (dead, ask) |
| listCards N656 | :154 | Vec<Card> rs/contact.rs:68 | 1320 <- ContactEngine | `List<Card>` |
| createCard N669 | :190 | Card | 1477 <- ContactEngine | `Card createCard(Transport, Account, String bookUrl, NewCard)` |
| readCard N683 | :233 | Card | 1491 <- ContactEngine | `Card` |
| updateCard N698 | :272 | Card | 1510 <- ContactEngine | `Card updateCard(Transport, Account, BookCard, String baseVcard)` |
| deleteCard N715 | :325 | `{}` | 1527 <- ContactEngine | `void deleteCard(Transport, Account, BookCard)` |
| createCards N731 | :372 | Vec<Card> | 1545 <- ContactEngine | `List<Card>` |
| deleteCards N739 | :415 | `{}` | 1556 <- ContactEngine | `void` |
| pushCards N749 | :457 | Vec<PushOutcome> rs/contact/push.rs:37; input Vec<PushChange> push.rs:13 | 1572 <- ContactEngine.pushRound 853 | `List<PushOutcome> pushCards(Transport, Account, String bookUrl, List<PushChange>)` |
| syncCards N767 | :503 | CardDelta rs/contact.rs:89 | 1341 <- ContactEngine | `CardDelta` |
| multigetCards N780 | :542 | Vec<Card> | 1375 <- ContactEngine | `List<Card>` |
| readGraphCards N794, readGoogleCards N802 | :587 :626 | Vec<Card> | 1391/1402 <- ContactEngine | `List<Card>` |
| updateCardBooks N809 | :665 | `{}` | 1460 <- ContactEngine (6 sites) | `void updateCardBooks(Transport, Account, BooksUpdate)` |
| indexCard N885 | :760 | CardIndex rs/contact/fields.rs:812 | Cards.indexCard <- ContactEditor, ContactEngine, ContactStager, PhoneRemote, PimdirContacts | static `CardIndex indexCard(String vcard)` |
| mergeCards N893 | :784 | CardsMerge merge.rs:196 | Cards <- ContactEditor | static `CardsMerge mergeCards(List<String>)` |
| mergeCardChanges N901 | :810 | ConflictMerge merge.rs:54 | Cards <- ContactPhonePass | static `ConflictMerge` |
| mergeConflictForm N910 | :837 | ConflictForm merge.rs:87 | Cards <- ContactEditor, ContactEngine | static `ConflictForm` |
| setCardUid N916 | :863 | VcardReply | Cards <- DuplicateReview | static `String` |
| findDuplicates N924 | :888 | Duplicates dedup.rs:25; input Vec<DuplicateCard> dedup.rs:14 | Cards <- DuplicateReview | static `List<DuplicateSet> findDuplicates(List<RefCard>)` |
| cardProps N931, cardSetProp N938, cardSetPropParts N946, cardPropLabels N954, cardSource N970 | :913 :936 :962 :991 :1031 | PropsReply/CardProp props.rs:19, VcardReply, LabelsReply | AdvancedEditor only (unreachable) | delete with AdvancedEditor (ask), else statics `List<CardProp>`, `String`, `List<String>` with `PropParts` input |
| cardTypeOrder N963 | :1012 | TypeOrderReply | Cards <- TypeTablesTest (test only) | static `List<List<String>> cardTypeOrder(TypeKind)` |
| projectCard N976 | :1054 | ContactFields fields.rs:52 | Cards <- PhoneRemote x3, ContactEditor, Birthdays, AdvancedEditor | static `ContactFields projectCard(String vcard)` |
| applyCard N983 | :1077 | VcardReply; input ContactFields | Cards <- ContactWriter x5, PhoneRemote, AdvancedEditor | static `String applyCard(String vcard, ContactFields model)` |
| formView N992 | :1106 | FormView form.rs:48 | Cards <- ContactForm | static `FormView formView(ContactFields)` |
| formEntry N1001 | :1130 | FormEntry (untagged) form.rs:160 | Cards <- ContactFieldDialogs x5 | statics per kind: `ContactPhone formPhone(int, String, boolean)`, `ContactEmail formEmail(...)`, `ContactRelation formRelation(...)`, `List<String> formAddressTypes(int)`, `ContactGender formGender(int, String)` (the caller always knows the kind; `Unset` unreachable) |
| formDate N1008 | :1156 | DateReply | Cards <- ContactFieldDialogs | static `String` |
| groupContacts N1017 | :1178 | ContactGroups group.rs:46; input ReplicaPool group.rs:14 | Cards <- ContactPool 64-72 | static `List<ContactGroup> groupContacts(ReplicaPool)` |
| duplicateGroup N1024 | :1201 | DuplicateGroup group.rs:129; input Vec<DuplicateMember> group.rs:118 | Cards <- DuplicateReview | static `DuplicateGroup duplicateGroup(List<DuplicateMember>)` |

**calendar (rs/ffi/calendar.rs) -> CalendarClient**

| Native | Rust | Reply type | Consumer today | Target |
|---|---|---|---|---|
| listCalendars N470 | :40 | Vec<Calendar> rs/calendar.rs:77 | 874 <- SyncPasses, OnboardingFlow | `List<Calendar>` |
| syncEvents N487 | :77 | EventDelta calendar.rs:117 | 906 <- CalendarEngine | `EventDelta` |
| multigetEvents N500 | :115 | Vec<Event> calendar.rs:101 | 934 <- CalendarEngine | `List<Event>` |
| expandEvent N515 | :162 | Vec<Occurrence> expand.rs:50 | 974 <- CalendarList | static `List<Occurrence>` |
| readEvent N524 | :374 | EventDetail read.rs:30 | 1012 <- EventView | static `EventDetail` |
| writeEvent N532 | :191 | raw iCalendar text or error; input EventEdit edit.rs:43 | 1060 <- EventWrites | static `String writeEvent(String ical, EventEdit)` |
| splitEvent N539 | :219 | SplitReply | 1146 <- EventWrites | static `EventSplit splitEvent(String, EventEdit)` |
| removeEvent N546 | :245 | raw text, empty for none | 1157 <- EventWrites | static `String removeEvent(String, EventEdit)` (null for none, as today) |
| mergeEvent N554 | :400 | EventMerge conflict.rs:136 | 1078 <- EventView, CalendarPhonePass | static `EventMerge` |
| resolveEvent N562 | :427 | raw text; input picks `{id: side}` | 1118 <- EventWrites | static `String resolveEvent(EventSides sides, Map<Integer, EventSide> picks)` with `EventSides(base, local, remote)` |
| projectEvent N571 | :322 | PhoneEvent phone.rs:173 | EventViews <- CalendarRemote x3 | static, `JSONObject` (recorded exception, challenge 2) |
| applyEvent N579 | :347 | raw text; input PhoneEdit phone.rs:187 | EventViews <- CalendarRemote | static, `JSONObject` input (same exception) |
| updateEvent N587 | :271 | JSON string etag or null | 1180 <- CalendarEngine | `String updateEvent(Transport, Account, EventTarget, String ical)` |
| newEvent N605 | :456 | raw text | 1232 <- EventView | static `String newEvent(EventDraft)` |
| createEvent N618 | :498 | EventRef calendar.rs:138 | 1257 <- CalendarEngine | `EventRef createEvent(Transport, Account, EventTarget, String ical)` |
| deleteEvent N633 | :544 | `{}` | 1289 <- CalendarEngine | `void deleteEvent(Transport, Account, EventTarget)` |

**mail (rs/ffi/mail.rs) -> MailClient** (MailSession stays)

| Native | Rust | Reply type | Consumer today | Target |
|---|---|---|---|---|
| openMailSession N303 | :49 | OpenedReply | openMail 784, MailSession.handleOf 136 | `static MailSession openMail(Account)` parsing package-private `Opened(boolean expungesOne, long handle)` |
| closeMailSession N307, joinMailRun N314 | :82 :415 | void | MailSession | unchanged |
| listMailboxes N258 | :99 | Vec<Mailbox> rs/mail.rs:157 | 428 <- MailEngine | `List<Mailbox>` |
| enumerateMailbox N272 | :124 | MailPage listing.rs:269 (engine wire) | enumerateMailboxRaw 458 + `PimalayaClient.reply` <- MailEngine 585-586 | `JSONObject enumerateMailbox(MailSession, String, JSONObject request)` (recorded exception); the Raw variant and `reply(String)` go |
| nameMessages N281 | :157 | NamedReply{items: Vec<Named>} | 470 <- MailEngine.read 843 | `JSONArray` (recorded exception, wire) |
| mailFloor N292 | :193 | FloorReply listing.rs:257 | mailFloor 490, mailNewest 502 <- MailEngine | `FloorReply floor(MailSession, String mailbox, String before, int count)`; mailFloor/mailNewest become `reply.floor()`/`reply.dates()` |
| mailStep N340 | :226 | MailStepReply (untagged) session.rs:283 | 532 <- MailEngine.stepped 1220 <- GmailStep/ImapStep/GraphStep | `NamedStep step(MailSession, StepRequest, File dir, OfflineDriver)` and `GraphStep graphStep(...)` over the same native (the caller knows its backend) |
| fetchMessageSources N356 | :269 | MessageSources session.rs:358 | 561 <- MailEngine.sources 1247 | `MessageSources fetchSources(MailSession, String mailbox, List<String> ids, File dir)` |
| parseMessage N367, parseMessageFile N374 | :310 :332 | MessageBody rs/mail.rs:175 | 596/604 <- MailReader | `MessageBody` (record) |
| describeMessage N384 | :358 | Description read.rs:206 | 616 <- MailEngine.stored 1106, MailReader | `Description` |
| messagePart N392 | :383 | SizeReply | 582 <- MessageView | `long` |
| setMessageFlag N400 | :434 | `{}` | 658 <- MailEngine | `void setFlag(MailSession, MessageRef, Flags.Marker, boolean add)` |
| composeMessage N419 | :613 | SourceReply (base64); input Draft rs/mail/compose.rs:37 | 687 <- MailSubmit.queue 93 | `byte[] compose(ComposeDraft)` |
| submitMessage N429 | :643 | SubmittedReply / RefusedReply / error | 706 <- SyncPasses | `String submit(MailSession, byte[])` unchanged contract (SubmissionRefused/Uncertain) |
| relocateMessage N437, copyMessage N445, destroyMessage N454 | :471 :507 :542 | `{}` | 735/749/759 <- MailEngine | `void relocate(MailSession, MessageRef, String target)`, `copy(...)`, `destroy(MailSession, MessageRef)` |
| appendMessage N462 | :572 | `{}`; input flags array | 770 <- MailEngine.append 1418 | `void append(MailSession, String mailbox, byte[] source, Flags)` |

**offline (rs/ffi/offline.rs, rs/logger.rs) -> OfflineClient, PimdirSql, Native**

| Native | Rust | Reply type | Consumer today | Target |
|---|---|---|---|---|
| offlineSync N829 | :37 | SyncReportJson rs/offline/wire.rs:1002 | offlineSync 1415 / offlineSyncImmutable 1430 <- ContactEngine, CalendarEngine, PhonePass, MailEngine (logged only) | `SyncReport sync(OfflineDriver, String collection, boolean full)`, `SyncReport syncImmutable(OfflineDriver, String, String since, boolean scopeBound)` |
| offlineUpgrade N843 | :72 | UpgradeReportJson wire.rs:1026 | 1442 <- PimdirEngine.hydrate 121, MailEngine | `UpgradeReport upgrade(OfflineDriver, String, List<String>)` |
| offlineMutate N852 | :110 | `{}`; input MutationJson wire.rs:584 | 1451 <- Stager.mutate 328 | `void mutate(OfflineDriver, String collection, Mutation)` |
| offlineRetryUnguarded N861 | :138 | RetryVerdict rs/contact/sync.rs:53; input RetryFacts sync.rs:36 | Cards <- ContactEngine.listingUnchanged 1178 | static `boolean retryUnguarded(RetryFacts)` |
| offlineAccountSnapshot N869 | :159 | AccountSnapshot sync.rs:111; input AccountSnapshotFacts sync.rs:85 | Cards <- ContactEngine.accountSnapshot 394 | static `AccountSnapshot accountSnapshot(AccountSnapshotFacts)` |
| offlinePushPlan N877 | :180 | PushPlan sync.rs:184 (PushAction 196); input PushPlanFacts 153 | Cards <- ContactEngine.pushPlan 1162 | static `PushPlan pushPlan(PushPlanFacts)` |
| pimdirSql N225, pimdirMigrations N240, pimdirVersion N228 | :208 :227 :244 | map, array, int | PimdirSql | unchanged (PimdirSql stays) |
| pimdirDerive N247 | :258 | DerivedReply | PimdirSql.derive 69 <- PimdirDb, DeletedItems, tests | `Derived derive(String kind, byte[] body)` |
| debugLogs N234 | rs/logger.rs:56 | void | Native static init | unchanged |

### A2. JSON-as-model sites in :app (with target)

Counts: JSON tokens / opt-get reads (today's tree).

| Site | What | Target |
|---|---|---|
| contact/Mapping.java 50/49 | ContactFields model <-> ContactsContract rows; merge 319 compares printed models | `ContactFields` records (B5); merge by record equality |
| contact/ContactForm.java 34/40, ContactFieldDialogs.java 27/38 | form edits the model JSON in place (ContactForm 40 doc, load 147, collect 176, putEntry 883) | `ContactFields.Builder` working copy; `FormView` (B6) |
| contact/ContactEditor.java 17/17 | CardsMerge, ConflictForm, CardIndex | records (B6/B7) |
| contact/ContactWriter.java 5/8 | applyCard model | `ContactFields` (B5) |
| contact/PhoneRemote.java 33/29 | projectCard/applyCard/indexCard + phone-spoke wire (enumerate 131, fetch 220, push 326, result 730) | model typed (B5); spoke wire stays (phase 8) |
| contact/ContactEngine.java 97/89 | facts (accountSnapshot 394, pushPlan 1162, listingUnchanged 1178), PushChange/PushOutcome (pushRound 853), engine wire (enumerate 344, snapshot 427, fetch 461, fetchedItem 606, push 642) | facts and push typed (B1, B4); wire stays |
| contact/ContactPool.java 11/5 | ReplicaPool input, ContactGroups reply | records (B7) |
| contact/DuplicateReview.java 10/6 | RefCard input, DuplicateSet/DuplicateGroup | records (B7) |
| contact/ContactPhonePass.java 8/5 | mergeCardChanges, wire | ConflictMerge (B5); wire stays |
| contact/PimdirContacts.java 13/23, ContactStager 3/2, Birthdays | indexOf 428 (CardIndex), projectCard | records (B5/B7) |
| contact/AdvancedEditor.java 21/16 | CardProp | delete (ask) or records |
| calendar/CalendarMapping.java 103/86, CalendarRemote.java 40/49 | PhoneEvent view, phone-spoke wire | deferred to phase 8 (challenge 2); only `EventTime.kind` becomes the enum |
| calendar/EventView.java 14/39 | page model JSON (`model`, `opened`, edited 1443, scoped 1644, copy 1410) | `EventEdit` built from an `EnumMap<EventEdit.Field, String>` page state (B3) |
| calendar/EventWrites.java 3/0, CalendarEngine.java 21/12 | edit strings, engine wire | `EventEdit` (B3); wire stays |
| mail/MailEngine.java 85/55 | pages (514-773 landing/listed/naming), held 477, fetchBodies 1043, stored 1106, stepRequest 1172, pushFlags 1431 | step/sources/description/flags typed (B8/B9); page and fetch/push replies stay wire |
| mail/GmailStep.java 19/17, GraphStep.java 13/12, ImapStep | step items | `NamedStep`/`GraphStep` records; the wire item for `land` built in one place (`MailEngine.wireItem`) |
| mail/MailStore.java 14/57 | flags column (has 2137, flagsOf 1915), queue payload reads (366, 720, 985, 2014, 2096), restateFromBody 1452 | `Flags` (B1b/C2), `Submission` (C1) |
| mail/MailSubmit.java 6/5, MailStager 3/0 | draft JSON 67-80, `new JSONArray().put(SEEN)` | `ComposeDraft` (B9), `Flags` |
| mail/MailWindow.java 3/12 | anchors JSONArray (anchor 284) | `List<String>` (B9) |
| mail/MailReader.java 3/5 | describeMessage | `Description` (B8) |
| pimdir/Stager.java 30/8 | mutations 201-300, has/withFlag 304/314, SEEN.. 181-195 | `Mutation`, `Flags` (B1) |
| pimdir/PimdirQueue.java 10/6 | Action.payload JSONObject 101, submission 352 | payload stays the persisted text; typed `mail.Submission` read/write (C1) |
| pimdir/DeletedItems.java 9/14 | flagsOf 324, withFlag 309 | `Flags` (B1b) |
| pimdir/PimdirEngine.java 27/18 | remote dispatch 133, named 243, result 350 | stays (wire, phase 8); `result` is the one push-result builder |
| pimdir/PimdirStorage.java 79/133, PhonePass 12/7, PhoneSpoke 5/0 | storage and spoke wire | stays (phase 8) |
| pimdir/PimdirSummary.java 18/6, PimdirItems.Row, FileStore.summary 665 | summary rows bound by column name | stays JSON (persistence format, plan rule) |
| account/SecureStore.java 18/19, setup/PendingGrantStore.java 5/3 | encrypted account store, pending grant | stays JSON (persistence); only AccountCredential's shape changes (B10), bytes pinned |
| setup/OauthFlow.java 7/18 | authorize extras 556, metadata | `Map<String,String>` extras, `ServerMetadata` record (B10) |

## B. Target :client

### B1. Layout

```
client/  Native (unchanged), Transport, MailSession, OauthSession, PimdirSql, OfflineDriver, PimalayaException,
         SubmissionRefused, SubmissionUncertain, Account (unchanged, phase 8)
         Replies (package-private: object/array/text/etag/failed/read, the error envelope)
         Json (package-private: field readers keeping today's defaults: optString, nullableString, list, ...)
         Clients (record: AccountClient account, ContactClient contact, CalendarClient calendar,
                  MailClient mail, OfflineClient offline; static Clients real())
         AccountClient, ContactClient, CalendarClient, MailClient, OfflineClient (non-final until phase 6)
         records/enums below, one file each (or nested in their owner when only it uses them)
```

`Replies` keeps today's exact behaviour and messages: `object` = PimalayaClient.object 1637 (`{"error", "status"?}` -> `PimalayaException(error, status)`); `array` = 1674 (an object where an array is expected: its error, else "Unreadable bridge reply: expected an array"); `text` = the writeEvent/newEvent/removeEvent/resolveEvent rule (trimmed, a leading `{` is the error shape; applyEvent's variant not trimmed, EventViews 33); `etag` = 1212; `failed` = 852 (string level, used by MailClient.on before parsing). `from` failures throw `PimalayaException("Unreadable bridge reply: ...")` as `string()` 1701 does.

Every record: public, components in the order Rust serializes them, `static X from(JSONObject)` package-private (and `static List<X> list(JSONArray)` where needed), Javadoc naming the Rust type and file:line. Inputs Java builds: public record with public canonical constructor and a package-private `JSONObject toJson()` (null component = key absent; empty list emitted as `[]` only where today's builder emits it). Components holding the pimdir summary are `JSONObject summary` (persistence format, opaque, read-only by contract).

### B2. Domain clients (public surface)

```java
public class AccountClient {
    public List<ServiceConfig> searchProvider(Transport t, String email, String resolver);
    public List<ServiceConfig> searchAll(String email, String resolver);           // fan-out kept as is
    public ServerMetadata serverMetadata(Transport t, String issuer);
    public String registerClient(Transport t, ClientRegistration registration);
    public OauthTokens refresh(Transport t, OauthGrant grant, String refreshToken);
    public static BackendInfo info(String url);                                    // memoized
    public static boolean isAccountLevel(Account a), isAccountLevel(String url), writesOverrides(String),
        writesExdates(String), writesEvents(String), submitsOverSession(Account), isCarddav(String),
        isGraph(Account), isGoogle(Account), isJmap(Account);                     // forwards over info()
    public static String googleCarddavBase(String), googleBase(String), msgraphBase(String),
        jmapBase(String), submitUrl(String), withUser(String url, String user);
}
public class ContactClient {
    public Set<String> jmapCapabilities(Transport t, Account a);
    public List<Addressbook> listAddressbooks(Transport t, Account a);
    public List<Card> listCards(Transport t, Account a, String bookUrl);
    public CardDelta syncCards(Transport t, Account a, String bookUrl, String token);
    public List<Card> multigetCards(Transport t, Account a, String bookUrl, List<String> uris);
    public List<Card> readGraphCards(Transport t, Account a, List<String> ids);
    public List<Card> readGoogleCards(Transport t, Account a, List<String> ids);
    public Card createCard(Transport t, Account a, String bookUrl, NewCard card);
    public Card readCard(Transport t, Account a, String bookUrl, String uri);
    public Card updateCard(Transport t, Account a, BookCard card, String baseVcard);
    public void deleteCard(Transport t, Account a, BookCard card);
    public List<Card> createCards(Transport t, Account a, List<String> vcards);
    public void deleteCards(Transport t, Account a, List<String> ids);
    public void updateCardBooks(Transport t, Account a, BooksUpdate update);
    public List<PushOutcome> pushCards(Transport t, Account a, String bookUrl, List<PushChange> changes);
    // pure
    public static ContactFields projectCard(String vcard);
    public static String applyCard(String vcard, ContactFields model);
    public static CardIndex indexCard(String vcard);
    public static CardsMerge mergeCards(List<String> vcards);
    public static ConflictMerge mergeCardChanges(String base, String local, String remote);
    public static ConflictForm mergeConflictForm(String base, String local, String remote);
    public static String setCardUid(String vcard, String uid);
    public static List<DuplicateSet> findDuplicates(List<RefCard> cards);
    public static FormView formView(ContactFields model);
    public static ContactPhone formPhone(int index, String number, boolean pref);
    public static ContactEmail formEmail(int index, String address, boolean pref);
    public static ContactRelation formRelation(int index, String value, boolean pref);
    public static List<String> formAddressTypes(int index);
    public static ContactGender formGender(int index, String identity);
    public static String formDate(int year, int month, int day);
    public static List<List<String>> cardTypeOrder(TypeKind kind);
    public static List<ContactGroup> groupContacts(ReplicaPool pool);
    public static DuplicateGroup duplicateGroup(List<DuplicateMember> members);
}
public class CalendarClient {
    public List<Calendar> listCalendars(Transport t, Account a);
    public EventDelta syncEvents(Transport t, Account a, String calendarUrl, String cursor);
    public List<Event> multigetEvents(Transport t, Account a, String calendarUrl, List<String> ids);
    public String updateEvent(Transport t, Account a, EventTarget target, String ical);
    public EventRef createEvent(Transport t, Account a, EventTarget target, String ical);
    public void deleteEvent(Transport t, Account a, EventTarget target);
    // pure
    public static List<Occurrence> expandEvent(String ical, String windowStart, String windowEnd);
    public static EventDetail readEvent(String ical, String recurrenceId);
    public static String writeEvent(String ical, EventEdit edit);
    public static EventSplit splitEvent(String ical, EventEdit edit);
    public static String removeEvent(String ical, EventEdit edit);
    public static EventMerge mergeEvent(String base, String local, String remote);
    public static String resolveEvent(EventSides sides, Map<Integer, EventSide> picks);
    public static String newEvent(EventDraft draft);
    public static JSONObject projectEvent(String ical, String now);      // recorded exception (phase 8)
    public static String applyEvent(String ical, JSONObject edit);       // recorded exception (phase 8)
}
public class MailClient {
    public static MailSession openMail(Account a);
    public List<Mailbox> listMailboxes(MailSession s);
    public JSONObject enumerateMailbox(MailSession s, String mailbox, JSONObject request);  // wire (phase 8)
    public JSONArray nameMessages(MailSession s, String mailbox, List<String> handles);      // wire (phase 8)
    public FloorReply floor(MailSession s, String mailbox, String before, int count);
    public NamedStep step(MailSession s, StepRequest request, File dir, OfflineDriver store);
    public GraphStep graphStep(MailSession s, StepRequest request, File dir, OfflineDriver store);
    public MessageSources fetchSources(MailSession s, String mailbox, List<String> ids, File dir);
    public MessageBody parseMessage(byte[] source);
    public MessageBody parseMessage(File source);
    public Description describeMessage(File source);
    public long messagePart(File source, String part, File target);
    public void setFlag(MailSession s, MessageRef message, Flags.Marker marker, boolean add);
    public byte[] compose(ComposeDraft draft);
    public String submit(MailSession s, byte[] source);
    public void relocate(MailSession s, MessageRef message, String target);
    public void copy(MailSession s, MessageRef message, String target);
    public void destroy(MailSession s, MessageRef message);
    public void append(MailSession s, String mailbox, byte[] source, Flags flags);
}
public class OfflineClient {
    public SyncReport sync(OfflineDriver d, String collection, boolean full);
    public SyncReport syncImmutable(OfflineDriver d, String collection, String since, boolean scopeBound);
    public UpgradeReport upgrade(OfflineDriver d, String collection, List<String> handles);
    public void mutate(OfflineDriver d, String collection, Mutation mutation);
    public static boolean retryUnguarded(RetryFacts facts);
    public static AccountSnapshot accountSnapshot(AccountSnapshotFacts facts);
    public static PushPlan pushPlan(PushPlanFacts facts);
}
// OauthSession: static start(OauthRequest), static resume(OauthRequest, String state, String verifier),
// authorizeUrl(String endpoint, Map<String,String> extras), redeem(String tokenEndpoint, String redirect[, String issuer]).
// PimdirSql.derive(String kind, byte[] body) -> Derived.
```

Method names: keep today's names in 5A (moves verbatim); the renames above (`floor`, `step`, `compose`, `submit`, `setFlag`, `relocate`, `sync`, `upgrade`, `mutate`, `refresh`, `registerClient`, `serverMetadata`) land with the typing step of each verb. `mailNewest` is dropped once callers read `floor(...).dates()`.

### B3. Records and enums (components in Rust serialization order; `?` = nullable; today's Java default noted where it differs from strict)

Account:
- `BackendInfo(boolean accountLevel, Backend backend, boolean submitsOverSession, boolean writesEvents, boolean writesExdates, boolean writesOverrides)`; `enum Backend {CARDDAV, GRAPH, JMAP, GOOGLE, LOCAL}` (wire: rs/account.rs 119-125 + "local" ffi/account.rs 640). Today missing flags read false (`optBoolean`); keep.
- `ServiceConfig(String service, String url?, String host?, int port, String security?, String username?, List<AuthMethod> auth, String source)`: keeps today's flattening (ServiceConfig.from 52: endpoint http|tcp, `provider:<name>` source); public canonical constructor (unblocks tests). Service/security/source stay Strings: vocabularies of io-pim-discovery, not this crate (record why in tasks.md).
- `AuthMethod(Type type, String authorizationEndpoint?, String deviceAuthorizationEndpoint?, String tokenEndpoint?, String scope?, String issuer?)`, enum Type as today; factories `password()`, `bearer()`, `codeGrant(...)`, `deviceGrant(...)`, `issuer(String)`.
- `OauthTokens(String accessToken, String tokenType, long expiresIn, String refreshToken?, String scope?, long issuedAt)`: order taken from a captured reply (io-oauth's serializer); defaults as OauthTokens.from ("Bearer", 0).
- `ServerMetadata(String issuer?, String authorizationEndpoint?, String tokenEndpoint?, String registrationEndpoint?, String scopesSupported)`; keeps the space-joined scopes and `supportsDynamicRegistration()`/`domainScope()`.
- Inputs: `OauthGrant(String tokenEndpoint, String clientId, String clientSecret?, String scope?)` (shared with :app AccountCredential), `OauthRequest(String clientId, String clientSecret?, String redirectUri, String scope)`, `ClientRegistration(String endpoint, String redirectUri, String clientName?, String scope?)`.

Contact:
- `Addressbook(String id, String name, String url, String description?, String color?, String role, boolean writable)`; writable absent -> true (402).
- `Card(String id, String uri, String etag?, String vcard, List<String> books)`; books absent -> empty; 4-arg constructor kept.
- `CardDelta(List<Card> changed, List<String> vanished, String token?, boolean complete)`.
- `PushOutcome(String ref, boolean accepted, String id?, String etag?, String error?)`; input `PushChange(String ref, PushOp op, String id?, String vcard?, String baseVcard?, List<String> add, List<String> remove)`, `enum PushOp {CREATE, UPDATE, BOOKS, DESTROY}` (rs push.rs `op: String`, Java's four values).
- Inputs `NewCard(String id, String vcard)`, `BookCard(String bookUrl, Card card)`, `BooksUpdate(String cardId, List<String> add, List<String> remove)`.
- `ContactFields(List<ContactAddress> addresses, String anniversary?, String birthday?, List<String> categories, String displayName?, List<ContactEmail> emails, ContactGender gender?, List<String> impps, List<String> languages, ContactName name?, List<String> nicknames, List<String> notes, ContactOrganization organization?, List<ContactPhone> phones, List<ContactRelation> relations, String role?, String title?, String uid?, List<String> websites)` + `Builder` (mutable lists; the form's working copy and Mapping.model's). Null = absent (Rust `None`), `""` = present-empty (`Some("")`): the distinction Mapping.model 179-192 relies on (it writes `"displayName": ""`, `"organization": {}`).
- `ContactAddress(String city, String country, String ext, String pobox, String postcode, boolean pref, String region, String street, List<String> types)`, `ContactEmail(String address, boolean pref, List<String> types)`, `ContactGender(String identity, String sex)`, `ContactName(String family, String given, String middle, String prefix, String suffix)`, `ContactOrganization(String company, String department)`, `ContactPhone(String number, boolean pref, List<String> types)`, `ContactRelation(boolean pref, List<String> types, String value)`.
- `CardIndex(String email, List<String> emails, String hash, String info, String name, String phone, String sortKey, JSONObject summary?)`.
- `CardsMerge(Map<String, List<String>> alternatives, List<String> changed, ContactFields model, String vcard)`; `ConflictForm(Map<String, List<String>> alternatives, List<String> changed, ContactFields model, boolean resolved, String vcard)`; `ConflictMerge(int conflicts, String vcard)`. Maps are sorted (BTreeMap): use `TreeMap`/`LinkedHashMap` in key order.
- `FormView(List<FormAddress> addresses, FormDate anniversary?, FormDate birthday?, List<Integer> emails, FormGender gender?, String name, String organization, List<Integer> phones, List<Integer> relations)`, `FormAddress(int index, String summary)`, `FormDate(int day, int month, int year)`, `FormGender(String identity, int sexIndex)`.
- `DuplicateSet(List<String> reasons, List<String> refs)`; inputs `RefCard(String ref, String vcard)`, `DuplicateMember(String ref, String book)`; `DuplicateGroup(String key, boolean linkable)`.
- `ContactGroup(String key, List<Integer> replicas)`; input `ReplicaPool(List<PoolReplica> replicas, Map<String, String> links, List<String> detached)` with `PoolReplica(String ref, String uid, String name, String id)` (links values nullable).
- `enum TypeKind {PHONE, EMAIL, ADDRESS, RELATION, GENDER}`.
- AdvancedEditor-only (if kept): `CardProp(List<PropComponent> components?, String line, String name, List<PropParam> params, String value)`, `PropParam(String name, List<String> values)`, `PropComponent(String name, String value)`, input `PropParts(String name, List<PropParam> params, String value, List<PropComponent> components?)`.

Calendar:
- `enum EventTimeKind {DATE, FLOATING, UTC, ZONED}` (zone.rs 30; wire lowercase); `EventTime(String time, EventTimeKind kind, String tzid, Integer offset?)`, `isDate()` kept; the String constants EventTime.DATE.. 30 go.
- `Calendar(String id, String name, String url, String description?, String color?, String role, boolean writable)`; `Event(String id, String etag?, String ical)`; `EventRef(String id, String etag?)`; `EventDelta(List<EventRef> changed, List<Event> bodies, List<String> vanished, String token?, boolean complete)` (bodies absent -> empty, skip_serializing_if).
- `Occurrence(String component, EventTime start, EventTime end, EventTime recurrenceId?, String summary, String location, boolean allDay)`.
- `EventDetail(...)` 21 components as read.rs 30-77, `EventDetail.Attendee(String name, String address, String status)`; component constants kept.
- `EventSplit(String master, String series?)`.
- `enum EventSide {LOCAL, REMOTE}`, `enum EventConflictKind {FIELD, RECURRENCE}`; `EventMerge(String ical, boolean resolved, List<Conflict> conflicts)`, `Conflict(int id, EventConflictKind kind, String component, EventTime recurrenceId?, String field, EventSide series?, List<Choice> choices)` (series is `Option<EventSide>`, today a String: conflict.rs 117), `Choice(EventSide side, String value, EventTime time?, EventTime end?)`. EventMerge.LOCAL/REMOTE/FIELD/RECURRENCE constants go.
- Inputs: `EventEdit(...)` = edit.rs 43 (summary, description, location, url, status, categories, priority: String?; start, end, due, completed: DateEdit?; percentComplete, stamp: String?; scope: EventScope; recurrenceId, recurrenceInstant, uid, vtimezone: String?) + `enum Field` (wire key of each text field) for EventView's key-indexed page; `DateEdit(String time, EventTimeKind kind?, String tzid)` (EventView 1457-1465 writes kind/tzid only when the zone changed: keep, null = absent); `enum EventScope {ALL, THIS, FOLLOWING}`; `EventTarget(String calendarUrl, String id, String etag?, String address?)`; `EventDraft(String component, String uid, String stamp, String start, String tzid, String vtimezone)`; `EventSides(String base?, String local, String remote)`.

Mail:
- `Mailbox(String name, String role)`.
- `FloorReply(String floor?, int dated, List<String> dates)`.
- `NamedStep(List<String> anchors?, boolean byReception, boolean exhausted, String historyId, List<NamedStepItem> items)`, `NamedStepItem(List<String> flags, String handle, String linkId, List<String> mailboxes, String path, Long received?, long size, String sortKey, JSONObject summary?)` (sortKey absent -> ""); `GraphStep(boolean exhausted, List<GraphStepItem> items)`, `GraphStepItem(String handle, List<String> mailboxes /*elements nullable*/, String path?, Long received?, Long size?, String sortKey)`.
- `MessageSources(List<String> gone, List<MessageSource> items)`, `MessageSource(String id, List<String> labels? /*Gmail only*/, String path, Long received?, long size)`.
- `MessageBody(String subject, String from, String fromAddress, String to, String cc, String date, String kind, String body, List<Attachment> attachments, boolean attachmentMark)`, `MessageBody.Attachment(String name, String mime, long size, String part)`; every string defaults to "" as message() 621 says.
- `Description(List<MessageBody.Attachment> attachments? /*null: unreadable*/, String hash, long size, JSONObject summary?)`.
- Inputs: `StepRequest(String mailbox?, int count, String frontier?, String bound?, List<String> anchors, List<String> mailboxes?)` (MailEngine.stepRequest 1172 + GmailStep 47-52 `mailboxes`; frontier/bound emitted as JSON null, as today); `ComposeDraft(String from, String fromName, String to, String cc, String bcc, String subject, String body, String date, String messageId, String inReplyTo, String references)` (MailSubmit 67-80 order); `MessageRef(String mailbox, String id)`.
- `Flags(List<String> markers)` with `enum Marker {SEEN("\\Seen"), ANSWERED("\\Answered"), FLAGGED("\\Flagged"), DELETED("\\Deleted")}`, `has(Marker)`, `with(Marker, boolean add)` (= Stager.withFlag 314: drop every occurrence, append at the end if added; order preserved), `static Flags parse(String storeText)` (unreadable or empty -> none, = MailStore.has 2137), `String text()`. Lives in :client: it is the marker vocabulary the bridge speaks (setFlag, append, mutations) and the store's flags column; pimdir and mail both read it.

Offline:
- `SyncReport(int conflicts, int pulled, int pushed, int refreshed, int rejected, int waiting)`, `UpgradeReport(int deduped, int fetched, int upgraded)`.
- `Mutation`: one record, op-tagged, factories mirroring Stager 201-300: `setFlags(handle, Flags)`, `remove(handle)`, `move(handle, target)`, `copy(handle, target)`, `edit(handle, String body, JSONObject summary, String sortKey)`, `add(String linkId, String body | byte[] body, Flags, JSONObject summary, String sortKey)`; hash/size computed inside (PimdirHash is :app: pass hash and size in, or move PimdirHash's one function to :client; decide in B1, record it). `toJson` keeps Stager's key order per op.
- `RetryFacts(Map<String,String> listed?, boolean complete, String handle, String ifMatch?)`, `AccountSnapshotFacts(String bookId?, boolean complete, List<SnapshotCard> changed, List<String> vanished)`, `SnapshotCard(String handle, List<String> books, boolean known)`, `AccountSnapshot(List<Integer> members, List<String> vanished)`, `PushPlanFacts(PushPlanFacts.Op op, String collection, String bookId?, boolean origin, boolean deleted)` (`Op {ADD, REMOVE}`), `PushPlan(PushAction action, List<String> postCreateBooks?)`, `enum PushAction {MEMBERSHIP, CREATE, DELETE}`.
- `Derived(String linkId, String sortKey, JSONObject summary?)`.

Enum wire mapping: each enum has `String wire()` and package-private `static X of(String)` throwing `PimalayaException("Unreadable bridge reply: unknown <kind> '<v>'")`. Strict on purpose (the producer is this crate, the enum closed); recorded behaviour change: an unknown value used to pass as a String.

## C. :app changes

### C1. Per area

- composition: `PimalayaClient client` -> `Clients clients` (MainActivity 121, App 108, BackgroundJob 87, SyncService 87, PimdirContacts 67, CalendarPhonePass 40, SyncPasses.Parts 93-94, MailParts, ContactParts, CalendarParts). PimdirEngine/Stager/PhonePass take `Clients`; engines read `clients.offline()` and their domain client. Test fakes subclass the domain client and pass it in `new Clients(...)`.
- pimdir: Stager mutations build `Mutation`; `Stager.SEEN/ANSWERED/FLAGGED/DELETED/has/withFlag` 181-322 replaced by `Flags`; DeletedItems.flagsOf 324 returns `Flags`; PimdirStorage.baseFlags 1232 returns `Flags` (its body stays JSON, phase 8). One push-result builder: PimdirEngine.result 350 (CalendarRemote.result 637, PhoneRemote.result 730 call it) and one held-reply builder; that is the "built in one place" for the wire until phase 8.
- contact: ContactEngine facts and push through records (accountSnapshot 394, pushPlan 1162, listingUnchanged 1178, pushRound 853 -> `List<PushChange>`/`List<PushOutcome>`); Mapping on `ContactFields` (rows(ContactFields), model(...) -> ContactFields, merge(ContactFields, ContactFields) -> ContactFields? by component equality on the 12 FIELDS, FIELDS becoming an enum with getter and builder setter); PhoneRemote.contentValues 535 takes ContactFields; ContactWriter save* take ContactFields; ContactForm/ContactFieldDialogs edit a `ContactFields.Builder` (load(ContactFields, Map<String,List<String>> alternatives, List<String> changed), collect() -> ContactFields); ContactEditor/DuplicateReview/ContactPool/PimdirContacts/ContactStager/Birthdays on records.
- calendar: EventTime.kind enum across CalendarMapping (time 1524/json 1535 convert through `EventTimeKind.of/wire`, internal JSON otherwise unchanged), Zones, EventView, CalendarList; EventMerge enums in EventView and CalendarPhonePass; EventView builds `EventEdit` (edited 1443, scoped 1644); EventWrites.Save.edited becomes `EventEdit`, picks `Map<Integer, EventSide>`; CalendarEngine uses EventTarget.
- mail: MailEngine: floor/step/sources/describe/flags typed, `held` 477 and the step-landing wire item built in one MailEngine method (`wireItem(NamedStepItem)`, replacing GmailStep.named 148); GmailStep/ImapStep/GraphStep read NamedStep/GraphStep; MailWindow.anchor takes `List<String>`; MailSubmit builds `ComposeDraft`; MailReader on Description/MessageBody; MessageView on `messagePart`.
- setup/account: OauthFlow builds OauthRequest/extras map, ServerMetadata/ClientRegistration; SyncPasses/TokenRenewal refresh with `OauthGrant`; AccountCredential reshaped (C3); SetupPlan/Endpoints tests (item 4).

### C2. Wide signatures (5+ parameters)

Rules (to add to java-0xx's note in tasks.md): (a) a record whose components mirror a producer's fields (a reply record, a store row read whole) is the type, not a call, and is exempt; (b) Android framework overrides are exempt (SyncDock.onLayoutChange 302, RecipientField.onLayout 202, SyncService/CalendarSyncService.onPerformSync, AuthenticatorService.addAccount 45, OpenedFiles.query 47); (c) existing params records are already the answer (EventWrites.Save 37, MailSubmit.Draft 54, SyncPasses.Parts 93, MailParts, ContactParts, CalendarParts); (d) everything else gets a params record named after the operation, or a reason in tasks.md.

| Today | Target |
|---|---|
| client: updateEvent 7, createEvent 6, deleteEvent 6 | `EventTarget` (B3) |
| client: newEvent 6 | `EventDraft` |
| client: oauthRefresh 6 | `refresh(Transport, OauthGrant, String)` |
| client: oauthRegisterClient 5 | `ClientRegistration` |
| client: OauthSession ctors 6/5 | `OauthSession.start(OauthRequest)`, `resume(OauthRequest, state, verifier)` |
| client: setMessageFlag 5, mailStep 5 | `MessageRef` + `Flags.Marker`; `StepRequest` carries the mailbox |
| client: updateCard 5, createCard 5, updateCardBooks 5 | `BookCard`, `NewCard`, `BooksUpdate` |
| client models: EventDetail 21, MessageBody 10, ServiceConfig 8, Addressbook/Calendar 7, Occurrence 7, EventMerge.Conflict 7, OauthTokens 6, AuthMethod 6, EventDelta 5, Card 5 | records mirroring replies, rule (a) |
| mail/MailStore.StoredMessage 15/16/14 (539, 559, 603) | split (C3) |
| account/AccountCredential 8 (70), oauth 6 (93) | `AccountCredential(String id, String login, String secret, Renewal renewal?)` with `Renewal(String refreshToken?, OauthGrant grant)` (null renewal = password credential; `renewable()` = renewal != null && refreshToken != null, as today); `oauth(OauthTokens tokens, OauthGrant grant)`; `withTokens` unchanged in meaning |
| setup/OauthFlow.launchSchemeGrant 9 (542), startLoopbackOauth 9 (599), promptOauthClient 8 (327), startIssuerOauth 7 (426) | `OauthLaunch(String email, String baseUrl, OauthRequest request, String authEndpoint, String tokenEndpoint, String resource, String issuer)` (data record) + `heading/own` in a `Prompt(String heading, boolean own)` |
| setup/OnboardingFlow.connect 7 (2098) | `connect(String email, OauthTokens tokens, OauthGrant grant)` |
| setup/OnboardingFlow ctor 6, AccountSettings ctor 7, SyncProgress ctor 6, Filters 6, FilesList 5, MessageView 5, DeletedItemsPage 5 | screen ctor: host + one `*Parts` record each (the phase 4 pattern) |
| pimdir/PimdirStorage.writeBinding 9 (1032), applyUpsert 6 (864), copyRows 6, carrySummary 6, applyBatchDrop 5 | `Binding(String collection, String source, String linkId, String handle)` + placement; the rest: reason "PimdirStorage, phase 8" |
| pimdir/PimdirSummary.mail 8 (226) | `MailSummary(messageId, subject, fromName, fromAddress, to, date, size, attachment)` record with `toJson()` (the row stays JSON) |
| pimdir/PimdirSummary.write 5, writeAddresses 5, address 5 | reason: SQL binding by column name (persistence contract) or `SummaryRow` |
| pimdir/PimdirCollections.upsert 7 (344), Stored 7/5 (167,156) | `CollectionRow(id, account, kind, name, description, color)` (Stored is the row record, rule a) |
| pimdir/PimdirQueue.enqueue 5 (130), Action 8 (101) | `enqueue(Enqueued(collection, accountEmail, kind, payloadText, body))`; Action becomes a record (rule a) with a typed payload reader |
| pimdir/Stager.mutateAdd 6/6, mutateEdit 5 | `Mutation` (B3) |
| pimdir/ItemLinks.search 6, Link 5, Placement 5 | `LinkSearch(kind, collections, pattern, limit)`; Link/Placement rule (a) |
| pimdir/DeletedItems.Row 10 | rule (a) (row read whole), flags as `Flags` |
| pimdir/PimdirDb.restateInvitation 5 | read during B-steps, decide |
| mail/MailStore.Edge 7/6, Query 5, queueSubmission 5 | Edge rule (a); `queueSubmission(Submission, byte[] source)` |
| mail/MailBodies.Row 7/6 | rule (a) |
| mail/WindowPicker.open 8, confirmRelease 5 | `WindowChoice` params record |
| mail/MailSubmit.stageSentCopy 6 | `SentCopy(sender, messageId, subject, to, date, source)` |
| mail/MailList.of 6, MailWindow.initial 5, WidenStep/ImapStep/GraphStep/GmailStep.run 5 | `StepRun(engine, collections, only, first)` shared by the four steps; others decide in D |
| calendar/EventStore.StoredCalendar 7, StoredEvent 6/5, StoredConflict 6 | rule (a) |
| calendar/CalendarEngine ctor 6, contact/ContactEngine ctor 5 | `pimdir.RemoteAccount(Transport transport, Account account, String accountId, String address)` |
| calendar/CalendarMapping.component 8, subRows 7, timeOf 6, Write 6, series 5, times 5, row 5; CalendarRemote.split 6, subAsserts 6, stamp 5, Target 5; Zones.observance 6; PhoneCalendars.set 5; CalendarRows.update 5 | reason: CalendarMapping/CalendarRemote/Zones are phase 8 (recurrence, spoke); CalendarRows.update and PhoneCalendars.set decide in D |
| contact/PhoneRemote.stampIfUnchanged 5, ContactEngine.snapshot 5 | snapshot takes `CardDelta`-like `Listing(cards, vanished, complete, token)`; stampIfUnchanged decide in D |
| contact/ContactForm.Field 6, ContactFieldDialogs.* 5 (281, 558, 603, 641), BookEntry 5 | UI builders: `DialogField(label, value, hint, inputType, ...)` per dialog family; Field rule (a) |
| file/FileStore.StoredFile 8, insert 6, place 5 | StoredFile rule (a); insert/place: `FileRow` |
| ui/Picker.choose 8, FilterPage.row 8, CardRows.domainRow 7, Sections.section 5, Chips.add/exclusive 5, PillField.of 5, Background.run 5, AccountSettings.choose 5, OnboardingFlow.domainRow 5/providerOption 5/Probe 5 | UI builders: one `RowSpec`/`PickSpec` record per builder family; any left gets a reason |

### C3. Flags, StoredMessage split, MailStore split (the phase 4 deferral)

- `Flags` (B3) replaces `seen/answered/flagged/deleted` booleans (MailStore 476-537, 632-644) and `MailStore.has` 2137; MailStager 51/100 and DeletedItems 309 use `Flags.with`; MailEngine.pushFlags 1431 reads `Flags`.
- StoredMessage: one row type stays (the merged list mixes both shapes, MailStore.loadMerged 697), built from two records:
  - `MessageHeader(String accountEmail, String collection, String mailbox, String id, String subject, String fromName, String fromAddress)`;
  - `StoredMessage.synced(MessageHeader header, Listing listing)` with `Listing(String mailboxLabel, String sortKey, long seq, Flags flags, Integer attachment, Long size, boolean bodied)` (today's ctor 603; stamp derived from sortKey as 631);
  - `StoredMessage.outgoing(MessageHeader header, long stamp, Flags flags, Queued queued)` with `Queued(long id, String objectHash, boolean failed, boolean uncertain)` (ctors 539/559; the `hasAttachment` MailStore.outgoing 719 passes moves onto `Queued`).
  - Public fields stay for the header parts (no churn on `message.subject`); `seen/answered/flagged/deleted` become methods over `flags`; `pending` = `queued != null` (24 uses unchanged as a method `pending()`). Every constructor at most 4 parameters.
- MailStore split (2150 lines) along the same seam, first steps of 5C:
  1. `mail.Outbox` (queue-backed outgoing messages): outgoing() 709/719, outgoing(email) 1998, acknowledge 2028, parkOutgoing 2067, parkUncertain 2077, resendOutgoing 2087, retryOutgoing 2108, queueSubmission 426, submitting 364, submissions 374, migrateOutbox 753 + Stranded, outboxOf 415, outboxName 2116, Outgoing 1975, the sent-alias trio 2038-2063. Typed `mail.Submission(int v, String from, String messageId, String subject, String sortKey)` replaces PimdirQueue.submission 352 and the five `action.payload.optString` reads; the payload text written stays byte-identical (same key insertion order; v=1), resendOutgoing 2096 re-enqueues the stored payload text verbatim (unknown keys kept).
  2. `mail.MailPolicy` owns MailStore's own prefs files (INBOX_ONLY, TRASH, ACCOUNT_WIDE, ALL_MAIL, MARKING, LINKS_REPAIRED, SENT_ALIAS; file names unchanged). MailScope, MailWindow, SenderName, MailPoison, MailOwed, MailOffline already each own one file: not merged (recorded: one owner per file already holds).
  3. MailStore keeps pages, queries, coverage, windows, edges, sources.

## D. Behaviour invariants and how each is verified

1. **Same parsed values.** Each record's `from` reproduces today's parser field by field, including defaults (optString "" vs null, `writable` true, `expungesOne` true, offset null on JSON null, absent object -> null time, absent books/bodies -> empty, MessageBody strings never null). Verified per record by a pin test in test/client/: the real bridge's reply string -> `from` -> assert values; plus `ReplyShape.assertMirrors(Record.class, rawReply)`: the record's component names (with the declared renames: `ref`, snake_case OAuth keys) equal the reply's key set, in the order the keys appear in the raw string (scan the raw text, not a re-printed JSONObject: challenge 8). A Rust field added later fails this test.
2. **Error envelope.** `Replies` keeps PimalayaClient.object/array/read/etag/failed and the text-reply rule byte for byte; RepliesTest (from PimalayaClientTest's 5 cases) + one case per text verb (`{"error"}` on writeEvent/removeEvent/newEvent/resolveEvent/applyEvent) + submit refused/uncertain/plain. MailClient.on keeps the string-level `failed` check before parsing, so the reopen-and-retry decision is unchanged.
3. **Java-built inputs, same effect.** Before each typing step, the editing agent captures today's builder output for a fixture set as literal JSON in the new test (run once on the old code). After: `toJson()` equals it as a JSON value (order-insensitive, null distinct from absent, `[]` distinct from absent only where Rust distinguishes it). Where the consumer is pure, also compare the bridge's answers for old literal vs new record: applyCard (same vCard out), formView, groupContacts, findDuplicates, duplicateGroup, writeEvent/splitEvent/removeEvent (same iCalendar), resolveEvent, offline facts verbs, compose (same message bytes for fixed date/messageId). Network inputs (PushChange, StepRequest, mutation): value equality only.
4. **Mapping.merge.** Today it compares `String.valueOf(opt(field))` of two models both built by `model()` (Mapping 330-333). After: `Objects.equals` of the field's component on two `ContactFields` both built by `model()`. Verified by MappingTest (16) unchanged plus a new corpus test: for N vCards x phone row sets, the merged model sent to applyCard yields the same vCard as the pre-change JSON did (literal fixtures captured first), and `null` (no edit) in exactly the same cases.
5. **Persisted bytes unchanged.** SecureStore.encode 363 and decode 440 (AccountCredential reshaped behind them), PendingGrantStore, queue payload (Submission), summaries: exact-string tests on the written text for fixed inputs, run where key order is meaningful (decide per challenge 8; if the JVM org.json reorders, compare the decoded objects and keep the encoder untouched line for line, reviewed by diff).
6. **Enums.** Every variant of each enum is produced by the real bridge in a pin test (crafted ics for the four EventTimeKind; a merge fixture with a recurrence conflict and both sides; a Google book for PushAction.MEMBERSHIP/CREATE/DELETE...). Unknown value -> PimalayaException (recorded change).
7. **Logs.** `Log.d(... + report)` prints `SyncReport[...]` instead of the JSON (ContactEngine 256, PhonePass 59, PimdirEngine 121, MailEngine 550): recorded, no test reads them; or give the records a `toString` printing the old JSON if the user wants identical logs.
8. **JNI.** Native.java untouched; `nm -D --defined-only rust/target/debug/libpimalaya.so | grep -oE "Java_\w+|JNI_\w+" | sort` diff empty each step; rust/ untouched.
9. **Moves verbatim (5A).** The multiset-of-lines check of phase 1 (scratchpad/lines.sh Java twin) over :client + :app, only receivers/imports/declarations differing.

## E. Ordered steps (one editing agent at a time; each green)

Gate after every step: `nix develop --command gradle -p android :app:assembleDebug :app:testDebugUnitTest :client:testDebugUnitTest --console=plain`, LayeringTest green, test count never lower (596 @Test methods in :app today), JNI symbol diff empty, snapshot diff reviewed. Device smoke points marked [device].

**5A: split :client (moves only, JSON returns allowed until 5B)**
- A0. User decisions (challenges 1, 2, 4, 5, 8-probe); add :client tests to the gate; org.json probe test (which implementation and key order).
- A1. `Replies` + `Json` out of PimalayaClient (verbatim); Cards, EventViews, OauthSession, ServerMetadata, MailSession, PimdirSql call them; PimalayaClientTest -> RepliesTest.
- A2. Five domain clients + `Clients` record by moving PimalayaClient/Cards/EventViews methods verbatim (statics for pure verbs, instance for transport/session verbs, offline facts verbs into OfflineClient); PimalayaClient/Cards/EventViews become forwarders.
- A3. :app off PimalayaClient/Cards/EventViews, by package, each sub-step green: (a) composition roots + pimdir + sync (Clients through Parts), (b) contact + its 6 test fakes, (c) calendar + fakes, (d) mail, (e) setup/account/file/link/trash/ui + e2e.
- A4. Delete PimalayaClient, Cards, EventViews (`reply(String)` goes: MailEngine 586 calls `MailClient.enumerateMailbox` returning the checked object; enumerateMailboxRaw goes). Dead wrappers dropped per A0.

**5B: typed replies and inputs (record + verb + consumers + pins per step)**
- B1. offline: SyncReport, UpgradeReport, facts records and replies (RetryFacts..PushPlan, PushAction), Mutation; Stager on Mutation. Pins: facts verbs on the real bridge; mutation value equality.
- B1b. `Flags` (client) replacing Stager.SEEN/has/withFlag, MailStore.has, DeletedItems.flagsOf, MailStager, MailEngine.pushFlags/append; `Derived` for PimdirSql.derive.
- B2. calendar models to records: EventTime + EventTimeKind, Calendar, Event, EventRef, EventDelta, Occurrence, EventDetail, EventSplit, EventMerge + EventSide/EventConflictKind; accessor churn in calendar/ and tests; EventTarget/EventDraft/EventSides params. Pins: expand/read/merge/split on crafted ics; DAV fixture for listCalendars/syncEvents/multiget/create/update/delete.
- B3. EventEdit/DateEdit/EventScope; EventView page state as EnumMap; EventWrites.Save typed. Pins: writeEvent/splitEvent/removeEvent same output for captured edits. [device: event edit this/following/all, delete occurrence, new event]
- (optional B3b if the user overrides challenge 2: PhoneEvent family + CalendarMapping/CalendarRemote on records. [device: calendar phone mirror both ways])
- B4. contact network records: Addressbook, Card, CardDelta (accessor churn: 92 sites), NewCard/BookCard/BooksUpdate, PushChange/PushOutcome in ContactEngine.pushRound/pushGoogle. Pins: DAV fixture for listAddressbooks/listCards/syncCards/multiget/create/read/update/delete; pushCards on literal fixtures (JMAP/Graph only).
- B5. ContactFields family + projectCard/applyCard typed; Mapping, PhoneRemote, ContactWriter, Birthdays, ContactPhonePass (ConflictMerge) on records; a temporary `projectCardJson` kept only for the form until B6 (deleted there). Pins: projectCard on a vCard corpus; Mapping corpus test (D4). [device: phone mirror contact edits both ways]
- B6. form side: FormView, per-kind formEntry, formDate, CardsMerge, ConflictForm; ContactForm/ContactFieldDialogs/ContactEditor on ContactFields.Builder. [device: contact edit, merge, conflict form]
- B7. contact rest: CardIndex, RefCard/DuplicateSet, DuplicateMember/DuplicateGroup, ReplicaPool/ContactGroup, TypeKind, setCardUid; PimdirContacts, ContactStager, DuplicateReview, ContactPool; AdvancedEditor deleted or typed per A0. [device: duplicates review]
- B8. mail replies: Opened, Mailbox, FloorReply (mailFloor/mailNewest callers), MessageSources/MessageSource, Description, MessageBody record, messagePart. Pins: parse/describe/part on a message corpus; IMAP scripted session for listMailboxes/floor/fetchSources (or literal fixtures + shape check, recorded).
- B9. mail inputs and step: StepRequest, NamedStep/GraphStep, MessageRef, ComposeDraft; GmailStep/ImapStep/GraphStep/MailEngine/MailSubmit/MailWindow. Pins: compose same bytes; step on IMAP script or fixtures. [device: first sync, Gmail step, compose and send, flag/delete]
- B10. account: BackendInfo/Backend, ServiceConfig/AuthMethod records (public constructors), OauthTokens, ServerMetadata, OauthGrant/OauthRequest/ClientRegistration, OauthSession factories; AccountCredential(id, login, secret, Renewal); SecureStore bytes pinned. New tests: SetupPlan.resourceOf, Endpoints.submissionUrl/endpointUrl, PimDomain.configsFor, and optionsFor's pure core moved into SetupPlan (labels passed in). [device: onboarding password + OAuth scheme + loopback, token renewal]
- B11. audit: `grep -n "public .*JSON\(Object\|Array\)" client/` lists only the recorded exceptions (enumerateMailbox, nameMessages, projectEvent, applyEvent; JSONObject summary components); tasks.md records them with reasons.

**5C: mail store**
- C1. `Submission` + PimdirQueue.Action as a record, payload text unchanged.
- C2. StoredMessage built from MessageHeader + Listing/Queued + Flags.
- C3. `Outbox` out of MailStore. C4. `MailPolicy` (MailStore's 7 prefs files). [device: mail list, outbox send/park/resend, Gmail All Mail, trash]

**5D: params records left** (table C2), by package: pimdir, mail, calendar/contact/file, setup, ui; each method either a params record or a reason line in tasks.md. [device after setup/ui: onboarding, account settings, filters, pickers]

**Defer** (record in tasks.md): the offline-driver wire and every page/fetch/push reply builder (phase 8); CalendarMapping/PhoneEvent (phase 8 unless the user overrides); PimdirStorage typing (phase 8); Account tuple (phase 8); text-reply verbs' error sniffing (phase 8: Rust replying `{ical}`).

## F. Phase 8 candidates found

1. Engine page naming in Java: PimdirEngine.named 243/keepBoundNames 196, MailEngine.nameUnnamed 683/nameByMessageId 796/steppedIn 742/landed 597. Rust can ask the store (a `bound` yield exists in spirit: offline.bound) and name members itself; then enumerateMailbox/nameMessages stop crossing as JSON.
2. ContactEngine facts round trips: accountSnapshot 394, pushPlan 1162, listingUnchanged 1178 are three JNI calls per decision Java assembles from store reads; the engine could decide inside the push/enumerate.
3. Read-modify-write of whole flag sets (MailStager 51/100, DeletedItems 309, MailEngine.pushFlags 1435 over baseFlags): a mutation carrying add/remove of one marker would let Rust apply it, no Java flag logic.
4. searchAll 83: Java fans out six mechanisms, merges via searchMerge, skips probes by reading `source` on raw JSON; Rust could plan the sweep and ask Java only for parallel transports. ServiceConfig's flattening (http|tcp, `provider:` prefix) reshapes Rust's shape.
5. ServerMetadata joins `scopes_supported` to a string that oauthDomainScope re-splits.
6. Text-reply verbs signal errors by a leading `{` (writeEvent, removeEvent, newEvent, resolveEvent, applyEvent) and updateEvent answers a bare JSON string: a uniform `{ical}`/`{etag}` reply removes Java's sniffing (Replies.text/etag).
7. Backend branching on BackendInfo: 40 predicate uses (already listed); BackendInfo is the typed form to delete then.
8. GmailStep distributing step items to collections by mailbox names (GmailStep 60-95) and rebuilding wire items: Rust could answer per mailbox.
9. ContactPool assembling ReplicaPool from store rows for groupContacts, then mapping indexes back: the store could feed Rust directly once PimdirStorage moves.
10. Dead exports `discover`, `listAccountCards` (Java unused): Rust exports removable in phase 8 (symbols change there, not here).
11. DeletedItemsPage parses retainedAt; MailStore.restateFromBody takes a summary JSONObject from describe: summary derivation already Rust's, the restate could be one Rust call.

## G. Risks

- Scale and churn: record accessors change `x.field` to `x.field()` at ~300 sites (Card 92, calendar times 107, Account left alone); compiler-driven but big diffs: one type family per step, reviewed by diff.
- Null vs absent on inputs: ContactFields and EventEdit carry `Option` semantics where `Some("")` differs from `None` (Mapping.model writes present-empty on purpose). A record that turns "" into null, or omits an empty object, changes vCards silently. D3/D4 by effect catch it; keep `null = absent` documented on every input record.
- org.json implementation mismatch (JVM tests HashMap order vs device insertion order): order assertions only on raw strings; persisted encoders not rewritten.
- Strict enums: a reply value the old code tolerated now throws. Producer is this crate, so only a bridge bug triggers it; recorded.
- JSONObject components (summary) make those records' equals identity-based; nobody compares them; Javadoc says read-only.
- Test seams: domain clients non-final and subclassed by tests until phase 6; avoid adding protected members for tests (java guideline); fakes override public verbs only.
- Pins for network verbs need fixtures (DAV over local http, IMAP script). If the bridge refuses plain http for some backend, fall back to literal fixtures + shape check, recorded per record.
- UI steps (B3, B6, B9, B10, C2-C4, 5D ui) are only device-verifiable: stop for the user's smoke tests at each [device] mark.
- D8: records already ship (SyncPasses.Parts, CalendarParts, EventWrites.Save); no sealed interfaces used, so no D8 check needed.
- Phase overlap: 5C touches the hottest store paths (MailList pages, fill, bodies); StorePerformanceTest guards speed, BodyLossRacesTest the races.
- AdvancedEditor/dead-wrapper deletions and CalendarMapping deferral need explicit user answers before 5A; without them B7 and B3b grow.

### Critical Files for Implementation
- android/client/src/main/java/org/pimalaya/client/PimalayaClient.java
- android/client/src/main/java/org/pimalaya/client/Cards.java
- android/app/src/main/java/org/pimalaya/contact/Mapping.java
- android/app/src/main/java/org/pimalaya/mail/MailStore.java
- android/app/src/main/java/org/pimalaya/pimdir/Stager.java

## Orchestrator decisions (2026-10-14)

1. Offline-driver wire stays JSON until phase 8 (recommended), enumerateMailbox and nameMessages are recorded JSON exceptions.
2. CalendarMapping and the PhoneEvent view are deferred to phase 8 (recommended); projectEvent/applyEvent stay JSON exceptions; step B3b is not done.
3. Contact Mapping is typed now.
4. AdvancedEditor (unreachable) is neither deleted nor typed: its six verbs stay JSON exceptions until the user decides whether the advanced/source panel goes.
5. discover and listAccountCards (no :app caller) are not carried into the domain clients: dead code is deleted, the natives stay.
6. Pins live in :app's test tree under package org.pimalaya.client; `:client:testDebugUnitTest` joins the gate.
7. Order assertions run on raw strings; persisted encoders untouched.
