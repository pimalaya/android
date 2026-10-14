# Phase 2 design: dependency direction (java-domain-layout)

Paths are relative to `android/app/src/main/java/org/pimalaya/` unless they start with `test/` (= `android/app/src/test/java/org/pimalaya/`). Line numbers are of today's tree (after phase 1), verified.

Deviations from plan section 5, to record in tasks.md: `sync` is split by direction. Its low half goes down (PimdirEngine to `pimdir`, the Rust `offline` counterpart; MergedFilter/SyncScope/TokenRenewal to `account`). Its orchestration stays in `sync`, now ABOVE the domains, which is where the phase 3 SyncCoordinator must live. `Accounts` (AccountManager plumbing) goes to `phone`. Four new packages: `setup`, `filter`, `trash`, `link`. DeletedItems, ItemLinks and DefaultCollection stay in `pimdir` once their mail edges are cut.

## A. Packages, bottom to top, and the gate

| package | role |
|---|---|
| `client` (:client) | JNI bridge, unchanged |
| `account` | who takes part: PimDomain, AccountEntry, AccountConnection, AccountCredential, AccountActivation, SecureStore, Endpoints, Oauth, TokenRenewal, LocalBook, MergedFilter + SyncScope (which accounts and collections a list shows and a sync takes) |
| `phone` | the platform mirror: PhoneMirror, PhoneQueue, Accounts (Android accounts) |
| `pimdir` | the store and its engine base (Rust `offline`): PimdirDb (+ new Locks), PimdirStorage, PimdirItems, PimdirQueue, PimdirCollections, PimdirSummary, PimdirBlobs, PimdirHash, PimdirAccount, PimdirEngine, MailDate, DeletedItems, ItemLinks (store half), DefaultCollection |
| `ui` | shared widgets and the screens' base activity: Host (new), Ui, Sections, Chips, PillField, ListHeader, CardSections, Avatar, Dates, SyncDock |
| `file` | FileStore, FileActions, FilesList, OpenedCopies (new) |
| `contact` | unchanged minus LocalBook |
| `calendar` | unchanged |
| `mail` | unchanged minus MailDate; MailState (new) |
| `sync` | orchestration across domains: SyncRunner, RemotePass, BackgroundCheck, FirstSync, SyncStamps, SyncSteps (phase 3: SyncCoordinator, WorkerPool) |
| `setup` | account setup and settings screens: OnboardingFlow, OauthFlow, PendingGrantStore, LoopbackRedirect, SetupSwitches, AccountSettings |
| `filter` | FilterPage (phase 4 adds MainActivity's applyFilter/firstOfMailboxes) |
| `trash` | DeletedItemsPage |
| `link` | LinkedSection |
| root | Android components (unchanged names), App + AppState (new), Domains, PermissionAnswer, CacheSweep |

`phone` and `pimdir` are siblings, and neither references the other. `filter`, `trash`, `link`, `setup` and `sync` do not reference each other, except `setup -> sync`.

LayeringTest at the phase 2 gate. These are the exact sets the code needs after section C.

```java
Map.entry(CLIENT,     Set.of()),
Map.entry("account",  Set.of(CLIENT)),
Map.entry("phone",    Set.of("account", CLIENT)),
Map.entry("pimdir",   Set.of("account", CLIENT)),
Map.entry("ui",       Set.of()),
Map.entry("file",     Set.of("ui", "pimdir", "account", CLIENT)),
Map.entry("contact",  Set.of("ui", "phone", "pimdir", "account", CLIENT)),
Map.entry("calendar", Set.of("ui", "phone", "pimdir", "account", CLIENT)),
Map.entry("mail",     Set.of("file", "ui", "pimdir", "account", CLIENT)),
Map.entry("sync",     Set.of("mail", "calendar", "contact", "phone", "pimdir", "account", CLIENT)),
Map.entry("setup",    Set.of("sync", "mail", "calendar", "contact", "phone", "pimdir", "ui", "account", CLIENT)),
Map.entry("filter",   Set.of("mail", "pimdir", "account")),
Map.entry("trash",    Set.of("contact", "pimdir", "account", CLIENT)),
Map.entry("link",     Set.of("mail", "contact", "ui", "pimdir")));
```

`calendar -> contact` is gone (rowHandle moves). `mail -> sync` is gone (expiredToken moves). Every `-> account` edge is now downward.

Tolerated until phase 4: a new `TOLERATED` map, file -> {ROOT}, checked before ALLOWED. It covers the 20 screens that hold `MainActivity host` and read its fields and its PANEL_/STEP_/REQUEST_ constants:

- contact/{AdvancedEditor, Birthdays, ContactsList, ContactWriter, DuplicateReview, VcfTransfer}
- calendar/{CalendarList, EventView}
- mail/{MailList, MessageCompose, MessageView, WindowPicker}
- file/{FileActions, FilesList}
- setup/{AccountSettings, OauthFlow, OnboardingFlow}
- filter/FilterPage, trash/DeletedItemsPage, link/LinkedSection

Reason, in one comment: a screen reaches its host activity until phase 4 gives screens their collaborators. Only ROOT is tolerated, and only for these files. No non-screen file and no `ui` file may reference root.

Phase 4 size (host decoupling), estimated from `host.` uses:
- Today about 1,000 `host.` uses across the 20 files.
- Step 14 below moves the shell services into `ui.Host`: io, main, ui, screen, dp, resolveColor, accentContrast, toast, postAlive, showError, message, hideKeyboard. After that about 135 distinct MainActivity-specific members remain: setup ~35, mail ~28, contact ~22, filter/trash/link ~12 each, calendar ~10, file ~8.
- Approach:
  - Move the PANEL_/STEP_/REQUEST_ constants onto `Host`. They keep compiling in MainActivity by inheritance.
  - Type `host` as `Host`.
  - Pass stores and sibling screens in by constructor.
  - Add a small per-package host interface implemented by MainActivity only where a screen needs several domain actions of the shell (setup, mail, contact).
- Roughly 6-8 agent steps.

## B. Classes whose package changes

| class | from -> to | reason |
|---|---|---|
| OnboardingFlow, OauthFlow, AccountSettings | account -> setup | screens over every domain, above sync |
| PendingGrantStore, LoopbackRedirect | account -> setup | package-private helpers of OauthFlow only |
| SetupSwitches | account -> setup | OnboardingFlow only; references PhoneMirror |
| Accounts | account -> phone | AccountManager plumbing of the phone mirror (books + calendars) |
| LocalBook | contact -> account | the synthesized local account, used by file, phone, sync, setup, trash |
| MergedFilter, SyncScope | sync -> account | the scope of lists and syncs; MailStore, the four lists and sync read it; it reads AccountActivation |
| TokenRenewal | sync -> account | credential plumbing over SecureStore |
| PimdirEngine | sync -> pimdir | base of the three domain engines; the Rust `offline` driver |
| MailDate | mail -> pimdir | PimdirSummary derives the stored date with it |
| FilterPage | ui -> filter | page, not widget; reaches mail (MailOffline) |
| DeletedItemsPage | ui -> trash | page; reaches contact (ContactEngine) |
| LinkedSection | ui -> link | page; reaches mail, contact |
| CacheSweep | pimdir -> root | start-up chore of the app over mail scratch and file staging, called by MainActivity only |
| SyncLock | sync -> deleted | folded into pimdir.Locks (D) |

New classes: root `App`, `AppState`; `ui.Host`; `pimdir.Locks`; `file.OpenedCopies` (members moved out of OpenedFiles); `mail.MailState`.

Tests move with their code:
- test/sync/PimdirEngineTest -> pimdir
- test/sync/TokenRenewalTest -> account
- test/MergedFilterTest -> account
- test/account/AccountsTest -> phone
- test/account/{LoopbackRedirectTest, PendingGrantTest, SetupSwitchesTest} -> setup
- test/mail/MailDateTest -> pimdir
- the `OpenedFiles.safe` cases of test/HardeningTest:81-96 -> new test/file/OpenedCopiesTest (`safe` stays package-private)

e2eTest sources compile in the test source set, so their imports follow too.

## C. Code changes that break the back edges (behaviour-preserving)

Package-level edges, all required for the gate:

1. pimdir -> mail (SQL plumbing).
   - Edge: `MailStore.typed` (1450), `changed` (1471) with `RETURNING` and private `bind`, and `likePattern` (987), all called from pimdir.
   - Change: move them as static members into `PimdirDb`. Repoint the callers:
     - PimdirDb 362, PimdirItems 535
     - ItemLinks 157/238/290/340
     - DeletedItems 355/398
     - FileStore x11
     - MailStore's own 15 calls
   - Phase 8: `likePattern` mirrors io-pimdir `reader::like_pattern`.
2. pimdir -> mail (All Mail).
   - Edge: PimdirCollections 180 reads `MailStore.ALL_MAIL` (MailStore 92).
   - Change: move the constant to `PimdirCollections.ALL_MAIL`; 12 refs in MailStore, MailEngine, GmailStep.
   - Phase 8: the sentinel is the Gmail step's (rust mail/gmail_step.rs).
3. pimdir -> mail (dates).
   - Edge: PimdirSummary 353 calls `MailDate.toStamp`.
   - Change: move the MailDate class to pimdir. MessageView 793 now imports it from pimdir.
   - Phase 8: PimdirSummary.rfc3339 re-derives what PimdirSql.derive answers.
4. pimdir -> file (role backfill).
   - Edge: PimdirDb 280 calls `FileStore.reconcileRoles` (FileStore 151).
   - Change: move `reconcileRoles` and `setRole` (168) into PimdirDb as private. Inline `FileStore.exec` (711) as `PimdirSql.bind` + `db.execSQL`, the same two lines as MailStore 197-199. Move `ATTACHMENTS` (48, private) and `ROLE` (51) to `PimdirCollections.ATTACHMENTS` / `ATTACHMENTS_ROLE`, next to ALL_MAIL; FileStore references them.
   - The call stays at the same point of the schema reconcile.
   - Phase 8: io-pimdir migration (§14.3).
5. pimdir -> mail (restore flags).
   - Edge: DeletedItems 308 calls `MailEngine.withFlag(..., MailEngine.DELETED, false)`.
   - Change: move `SEEN`, `ANSWERED`, `FLAGGED`, `DELETED` (MailEngine 68-81, ANSWERED made public), `has` (1524) and `withFlag` (1534) into PimdirEngine, beside `mutateFlags`/`mutateAdd`, which already carry the flags array. `WRITABLE` (92) stays.
   - Every `MailEngine.SEEN`/`has`/`withFlag` reference (15 main, about 40 test) still compiles through inheritance of static members, so no churn.
   - Phase 5: replace them with a typed Flags value.
6. pimdir -> sync.
   - Edge: DeletedItems 14 imports `sync.PimdirEngine`.
   - Change: none beyond the move in B; the edge becomes intra-package.
7. pimdir -> mail/calendar/file (endpoint factories).
   - Edge: ItemLinks `ofMessage`/`ofEvent`/`ofFile` (107/116/120).
   - Change: make the private `endpoint(kind, linkId)` (128) public as `ItemLinks.of`. Move the three factories to `MailStore.endpointOf(StoredMessage)`, `EventStore.endpointOf(StoredEvent)` and `FileStore.endpointOf(StoredFile)`. Callers: MessageView 186/266/274, EventView 545, FilesList 373. `ofContact` stays.
8. pimdir -> mail (link search).
   - Edge: ItemLinks.search 265-295 builds a MailStore to search messages.
   - Change: move the mail block (271-287) to `MailStore.linkPlacements(String words, int limit)`, which returns a list deduped as before. `ItemLinks.search` keeps the contact, calendar and file searches; make `FOUND` (37) public. The single caller, LinkedSection 300, concatenates `host.mail.linkPlacements(words, ItemLinks.FOUND)` and then `host.links.search(words)`.
   - Same order (mail first), and no cross-kind key collisions, so the result is identical.
9. pimdir -> mail/file (CacheSweep).
   - Edge: CacheSweep reads MailEngine.SCRATCH (27) and FilesList.STAGED (36).
   - Change: move the class to root; root may reference both.
10. phone -> contact/calendar (PhoneQueue).
    - Edge: PhoneQueue.start (60-82) builds a ContactEngine and calls CalendarEngine.phonePass.
    - Change: the wiring moves to `App` (D); the PhoneQueue constructor becomes public.
    - `written(url)` and `calendarWritten(collection)` become `written(Context, url)` and `calendarWritten(Context, collection)`, resolving the App-owned queue. They stay no-ops until started. Callers pass the context they hold:
      - ContactEngine 396: `pimdir.context()`
      - CalendarEngine 263: same
      - PimdirContacts 343/374: keep the `PimdirDb store` the constructor (63) already gets.
11. phone -> contact (BookEntry).
    - Edge: `Accounts.reconcile(Context, List<BookEntry>)` (73).
    - Change: the signature becomes `reconcile(Context, Map<Addressbook, String> accountByBook)`; the body reads `book` and `accountEmail` from the entry.
    - Add `static Map<Addressbook, String> BookEntry.accounts(List<BookEntry>)` (LinkedHashMap, same order).
    - Callers: AccountSettings 939, MainActivity 2961/3030, AccountsTest 66/81/88.
    - `LocalBook` (used by `name`, 300) is now in account.
12. account -> phone.
    - Edge: SecureStore 402 calls `Accounts.redacted`.
    - Change: move `redacted` (Accounts 49) to `AccountEntry.redacted`. Repoint MailBodies, MessageCompose, RemotePass x8, SyncRunner, AccountSettings 941, and Accounts' own use.
13. account (setup) -> account (low).
    - Edge: PendingGrantStore 45/67 calls `SecureStore.seal`/`open` (266/278, package-private).
    - Change: make both public. PendingGrantStore 73 -> `OauthFlow.expired` stays intra-setup.
    - Also make `Accounts.elsewhere`/`calendarsElsewhere` (174/204) public for setup.
14. calendar -> contact.
    - Edge: EventStore 214 and EventView 337 call `CardStore.rowHandle` (510).
    - Change: move `rowHandle` to `PimdirStorage.rowHandle`, next to `provisionalOf`, which is all it calls. Repoint ContactWriter 118/136/153 and MainActivity 3177.
    - Phase 8: provisional handles are SYNC §2.
15. mail -> sync.
    - Edge: MailEngine 1055 calls `SyncRunner.expiredToken` (433).
    - Change: move it to `PimdirEngine.expiredToken`, beside its status helpers. Repoint SyncRunner 182/276/386 and RemotePass 215.
    - This replaces the plan's "exception type": a move, no new type.
    - Phase 8: Java re-reads the BridgeError status.
16. pimdir -> ui-ish (dock text).
    - Edge: PimdirEngine 96 calls `SyncSteps.tells` (167).
    - Change: move `tells` into PimdirEngine. SyncSteps keeps the dock wording.
17. file -> root, mail -> root (OpenedFiles).
    - Edges:
      - FileStore 569 calls `OpenedFiles.clear`.
      - FileActions 84/123/185 and FilesList 555 call `typeOf`/`put`/`copy`.
      - MessageView 342 calls `partFile`.
    - Change: move the static half of OpenedFiles into `file.OpenedCopies`: DIRECTORY, LONGEST, put, safe, bytes, cut, extensionOf, typeOf, copy, partFile, clear, delete. The private `fileOf(Uri)` becomes `static fileOf(Context, Uri)`. The provider keeps its overrides and delegates. MainActivity 454 -> OpenedCopies.clear.
18. file -> mail (FilesList).
    - Edge: `fileOf(Row)` (391-404) and `showMessage(Origin)` (407-420) read `MailStore.StoredMessage`.
    - Change: move the message branches to MessageView as:
      - `File fileOf(FileStore.Origin, FileStore.StoredFile)`: lookup, the `files_no_message` throw, then the existing `fileOf(message, file)`.
      - `openOrigin(FileStore.Origin)`.
    - FilesList keeps the `host.files.saved` check and calls `host.messageView`. The remaining reach goes through the host and is part of the tolerated root edge until phase 4.
19. sync -> root.
    - Edge: BackgroundCheck.schedule (136-157) names `BackgroundJob.class`.
    - Change: the signature becomes `schedule(Context, Class<? extends JobService> job)`; MainActivity 540 passes `BackgroundJob.class`.
20. ui -> root.
    - Edges: Chips, PillField, ListHeader and SyncDock take `MainActivity`; ListHeader 53 calls `Domains.titleOf`.
    - Change: add a new abstract `ui.Host extends Activity`. MainActivity (133) extends it. Pure moves out of MainActivity:
      - fields `io` (194), `main` (205), `ui` (206), `screen` (362; MainActivity sets PANEL_ROOT in its initializer)
      - `accentContrast` (982), `resolveColor` (4653), `message`, `toast` (4710), `postAlive` (4720), `showError` (4734), `dp` (4742), `hideKeyboard`
    - Abstract on Host: `showBarTitle` (3783), `headerOf` (3792), `foldFab` (3836), and new `titleOf(int panel)` (MainActivity returns `Domains.titleOf`).
    - The widgets type `host` as `Host`. Screens keep compiling, since MainActivity is a Host.

Intra-package class cycles, left for later because the gate does not see them:
- contact: PimdirContacts 63 builds its ContactEngine stager, so CardStore -> PimdirContacts -> ContactEngine -> CardStore. Phase 3, stager split.
- calendar: CalendarRows 154 forwards to CalendarEngine.phonePass, which builds its own client (105). Phase 3 (stager split and phone-spoke contract).
- mail:
  - MailStore 1383 calls `MailList.roleLabel`.
  - MailStore 181/184/1676 read `MailEngine.collectionsOf`/`FIRST_CHUNK`.
  - Steps call back into MailEngine.
  - Phase 4 MailStore split; `mailboxLabel` moves next to `roleLabel` then.
- contact: Entry's constructor takes `PimdirContacts.Indexed`. Phase 5.

## D. Static mutable state: one process owner, one lock registry

Today:

| state | where | readers/writers |
|---|---|---|
| onScreen | MainActivity 228 | written 1001/1032, read 1081, BackgroundJob 89 |
| ran / running / onEnd | BackgroundJob 53/56/59 | MainActivity 231, 541, 777, 1004, 1049, 1871, 2081, 3066 |
| ingested | SyncService 37, CalendarSyncService 35 | MainActivity 234/237/3001/3012 |
| onWrite | MailEngine 108 | set by MailList 274, read by MailEngine 1251 |
| FAILED / GONE | MailBodies 43/52 | MailBodies, MailEngine 358/538/1001, MailList 1075/1078, MailBodiesTest |
| COLLECTED / PINNED | PimdirItems 464/470 | PimdirItems, PimdirStorage 729/1747/2040, DeletedItems 386/391, MailEngine 968/970/1116, BodyLossRacesTest |
| shared | PimdirDb 66 | callers are root only (+ CardStore 67, PhoneQueue 65) |
| shared / sharedOver | CardStore 56/59 | callers are root only (+ PhoneQueue 66) |
| shared | PhoneQueue 39 | |
| SHARED | MergedFilter 46 | a WeakHashMap keyed by the application context |

Owner design:
- Add `org.pimalaya.App extends Application`, registered as `android:name=".App"` on `<application>` (manifest line 31; none exists today). It is the only process holder.
  - Its constructor and `onCreate` stay empty: every field is lazy. Robolectric (`isIncludeAndroidResources = true`) builds it for each of the 531 tests.
  - Root reaches it by `App.of(context)`, which is `(App) context.getApplicationContext()`.
- App owns:
  - `AppState state`: a root, package-private class, `volatile` fields `onScreen`, `backgroundRan`, `backgroundRunning`, `onBackgroundEnd`, `contactsIngested`, `calendarsIngested`. It replaces the seven root statics.
  - `pimdir()` and `cards()`: synchronized and lazy. They replace `PimdirDb.shared` and `CardStore.shared`/`sharedOver`, whose callers are all root (SyncService 89-90, CalendarSyncService 92, ZoneChange 30, BackgroundJob 107-108, MainActivity 372-373). The PimdirDbTest 55-62 identity assertions become a root AppTest.
  - `phoneQueue()`, null until `startPhoneQueue()`, which MainActivity.onCreate calls where 545 calls `PhoneQueue.start` today, so the start timing is the same. The two consumer lambdas of PhoneQueue 71-81 move here.
  - The filter registry (`Map<String, MergedFilter>`) and a `mail.MailState`.
- Code below root never sees App. A package whose state App holds declares a one-method `Owner` interface beside that state, and App implements it:
  - `MergedFilter.Owner { Map<String, MergedFilter> filters(); }`
  - `PhoneQueue.Owner { PhoneQueue phoneQueue(); }`
  - `MailState.Owner { MailState mailState(); }`
  - Each static accessor, `MergedFilter.of(context, ...)`, `PhoneQueue.written(context, ...)` and `MailState.of(context)`, casts `context.getApplicationContext()` to its Owner. This is static lookup, not static state.
  - Call sites of `MergedFilter.of` are unchanged; the synchronization on the map is the same as MergedFilter 119.
  - These interfaces exist for layering (root implements them, nothing below names root), not for testability.
- `mail.MailState` holds `failed` and `gone` (the same concurrent sets), `volatile Runnable onWrite` and `relisted(collection)` (moved from MailBodies 60):
  - `MailBodies.Run` gets the state by constructor; MainActivity 2349 passes `MailState.of(this)`.
  - MailEngine reads `MailState.of(pimdir.context())` at 358/538/1001/1251.
  - MailList reads `MailState.of(host)` at 274/1075/1078.
  - MailBodiesTest drops its `clear()` calls (61-62): each test gets a fresh App. Its assertions read `MailState.of(context)`, so none is weakened.
- PimdirItems COLLECTED/PINNED become fields of the PimdirDb instance, as they guard that store's blob directory. The static `deferUnlink`/`pin`/`unpin`/`unlinkCollected(db, blobs)` take the PimdirDb their callers already hold. In production there is one PimdirDb per process, so this is equivalent.
- MainActivity field initializers 231/234/237 read the background and ingest stamps. They move to the top of onCreate, because `getApplication()` is null before `attach()`.

Locks: what each guards, and whether it merges.

| lock | guards | decision |
|---|---|---|
| PimdirEngine.STORE (51) | the one store writer: every storage yield, plus MailEngine x13, MailReader 86/125/148, GraphStep 149, CalendarEngine 173, DeletedItemsPage 333 | into `pimdir.Locks.store`; one PimdirDb per process, so the same single monitor |
| ContactEngine.SYNC_LOCKS (59) | one reentrant lock per book URL, around syncBook/syncPhone (195/257/295) | into `Locks.collection(kind, id)`, key `kind + "\n" + id`, so contact and calendar keys can never meet; same partition, same reentrancy |
| CalendarEngine.SYNC_LOCKS (59) | one reentrant lock per calendar collection id (130/217/246) | same as above |
| SyncLock.HELD (11) | one sync at a time, tryAcquire, taken by BackgroundJob 89 and MainActivity | into `Locks.takeSync()`/`releaseSync()`, same Semaphore(1) |
| TokenRenewal.LOCKS (41) | one monitor per OAuth credential refresh, over SecureStore | stays in account: it guards a credential, not the store, and account sits below pimdir |
| SecureStore.LOCK (43) | read-modify-write of the encrypted prefs across ad hoc instances | stays: one file, one monitor; merging changes nothing and inverts layering |
| SyncService/CalendarSyncService LOCK (33/31) + `adapter` | lazy creation of the sync adapter singleton (the Android sample pattern) | stay: a creation guard, not a registry |

`pimdir.Locks` is owned by the PimdirDb instance (`pimdir.locks()`), which App owns. Engines reach it through the PimdirDb they already receive, so no constructor changes. PimdirEngine exposes the store monitor as an instance field for its subclasses. The tests' `Thread.holdsLock(PimdirEngine.STORE)` (MailPoolTest 195, CalendarPoolTest 125/215) become `pimdir.locks().store`.

## E. Steps (one agent at a time; each leaves `:app:assembleDebug :app:testDebugUnitTest` green with test count >= 531, `nm` symbol diff empty)

| # | step | kind |
|---|---|---|
| 0 | LayeringTest: target ALLOWED map + TOLERATED list, still @Ignore | test only |
| 1 | setup package: OnboardingFlow, OauthFlow, PendingGrantStore, LoopbackRedirect, SetupSwitches, AccountSettings + 3 tests; widen SecureStore.seal/open and Accounts.elsewhere/calendarsElsewhere | pure move (multiset) |
| 2 | Accounts.redacted -> AccountEntry.redacted (C12) | member move |
| 3 | LocalBook, MergedFilter, SyncScope, TokenRenewal -> account, + MergedFilterTest, TokenRenewalTest | pure move |
| 4 | Accounts -> phone; reconcile takes the map; BookEntry.accounts (C11); AccountsTest -> phone | move + code |
| 5 | App + AppState + manifest; PimdirDb/CardStore shared -> App; MainActivity initializers to onCreate | code |
| 6 | PhoneQueue owned by App, Owner, written(Context, ...) (C10) | code |
| 7a | PimdirEngine -> pimdir (+ PimdirEngineTest) | pure move |
| 7b | tells, expiredToken, flags + has + withFlag into PimdirEngine (C5, C15, C16) | member moves |
| 8 | typed/changed/likePattern -> PimdirDb; ALL_MAIL, ATTACHMENTS -> PimdirCollections; reconcileRoles -> PimdirDb; MailDate -> pimdir; rowHandle -> PimdirStorage (C1-4, C14) | member moves (multiset modulo qualifiers); may be split per bullet |
| 9 | ItemLinks factories to the stores; link search split (C7, C8) | code |
| 10 | FilterPage -> filter, DeletedItemsPage -> trash, LinkedSection -> link; CacheSweep -> root | pure move |
| 11 | OpenedFiles helpers -> file.OpenedCopies; HardeningTest cases -> OpenedCopiesTest (C17) | member move |
| 12 | FilesList message branches -> MessageView (C18) | code |
| 13 | BackgroundCheck.schedule takes the job class (C19) | code |
| 14 | ui.Host; widgets typed Host; titleOf (C20) | member move + code |
| 15 | MergedFilter.Owner, MailState, COLLECTED/PINNED onto PimdirDb (D) | code |
| 16 | pimdir.Locks; SyncLock deleted (D) | code |
| 17 | remove @Ignore from LayeringTest; record deviations and phase 8 candidates in tasks.md | gate |

Order constraints:
- 5 before 6 and 15.
- 7a before 7b and 16.
- 1-4 are independent of 7-14.

Pure-move steps are verified with the phase 1 line-multiset script. Member moves are checked the same way after ignoring the qualifier tokens of the repointed call sites (list them in the report).

## F. Risks and device smoke test

Risks:
- App is new in the manifest, and every Robolectric test now runs `App`: its `onCreate` must stay empty.
- An `Owner` cast fails if a future test sets `@Config(application = ...)`; none does today, and failing fast is intended.
- Locks and the blob guard are now per PimdirDb. Two PimdirDb instances over one file would no longer share them. Production has one; check any test that opens two (the agent greps `new PimdirDb(`).
- `MailEngine.SEEN`-style access to statics declared in PimdirEngine, through inheritance, is legal but lint-visible; phase 5 removes it.
- LayeringTest only sees textual `org.pimalaya.x` references. Reach through MainActivity-typed fields (`host.mail`, `host.messageView`) is invisible, so the tolerated root edge hides a file->mail reach in FilesList and others until phase 4.
- Accounts.reconcile now takes an identity-keyed map. Addressbook has no `equals`, so the map must be a LinkedHashMap built once per call; it is.
- OAuth redirect intent filters target `.MainActivity`, which is unchanged. Launcher shortcuts, the scheduled job (`.BackgroundJob`) and the sync adapters keep their component names.
- JNI is unaffected (:client untouched); compare `nm` anyway.

Device smoke test at the phase 2 gate. Phase 2 touches the manifest and process state, so this pass is not left for phase 3:
- Cold launch.
- Onboarding with OAuth (password and browser grant).
- Account settings, including delete.
- A mail first sync, with the list filling live (onWrite).
- A failed or gone body shown on its row.
- A contact edit reaching the phone Contacts app within about a second (PhoneQueue).
- A calendar app edit coming back (CalendarSyncService ingest reload).
- A background check run while the app is closed, with its notification.
- The filter page, including a mailbox's "Download".
- Deleted items: restore a mail and a contact, then free space.
- Links: the add dialog search lists messages first.
- Files: open, export and import an attachment, and open a file's source message.
- The sync dock and the list headers (Host).
