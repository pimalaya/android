---
cairn: tasks
change: java-domain-layout
---

# Tasks

Paths are relative to android/. Every step ends with `:app:assembleDebug :app:testDebugUnitTest` green (531 JVM tests at start, never fewer), the exported JNI symbols diffed against the baseline (empty) and a review of the diff against a snapshot.

## Phase 0: groundwork

- [x] Gradle baseline green: 531 JVM tests, 102 exported symbols recorded
- [x] Java scope in the org GUIDELINES.md, java-001 to java-012 (drafted in .github, uncommitted, awaiting user review)
- [x] dependency-direction test LayeringTest (ignored until the phase 2 gate); e2e Gradle patterns match subpackages
- [x] stale docs/jni-boundary.md and docs/jni-typing-plan.md marked historical (rewritten in phase 7)

## Phase 1: moves only

- [x] pimdir
- [x] sync
- [x] phone
- [x] file
- [x] calendar
- [x] contact
- [x] mail
- [x] account
- [x] ui
- [x] shell (nothing left to move: the nine root classes)
- [x] OfflineEngine renamed ContactEngine (with its four tests)

## Phase 2: dependency direction

- [x] layering of phase2-design.md: account below sync, PimdirEngine and store plumbing into pimdir, sync = orchestration above the domains, new packages setup, filter, trash, link; cycles cut by member moves (design C1-C20; C19 revised to a BackgroundCheck.Owner)
- [x] App (manifest) owns AppState, the store, cards, phone queue, filter registry, MailState; blob pins on PimdirDb; Owner interfaces let lower packages reach App without naming it
- [x] pimdir.Locks on the one PimdirDb replaces PimdirEngine.STORE, both SYNC_LOCKS maps and SyncLock; TokenRenewal, SecureStore and adapter guards stay (design D)
- [x] LayeringTest enabled and green (20 screen files tolerated to reach the root until phase 4)

## Phase 3: engines

- [ ] stager split from remote pass, no null-selected roles
- [ ] WorkerPool
- [ ] SyncCoordinator out of MainActivity, SyncRunner and RemotePass
- [ ] one phone-spoke contract
- [ ] device smoke test owed: sync, background sync, phone mirror

## Phase 4: logic out of the UI

- [ ] account: SetupPlanner, commit/finish assembly, guarded posts
- [ ] mail: BodyPlan, compose draft and answered-flag rule, reply helpers
- [ ] contact: ContactEditor out of MainActivity; composeEvent to calendar
- [ ] calendar: EventView save decision
- [ ] account: deleteAccount cascade
- [ ] filters out of MainActivity
- [ ] ui: one adapter base, Sections everywhere, one dp/resolveColor, one background-work helper, Dates everywhere
- [ ] MailStore and PimdirStorage split along their seams
- [ ] device smoke test owed: onboarding, contact edit, event edit, account deletion

## Phase 5: typed data

- [ ] :client records per Rust reply, enums for serde enums, five domain clients, no public JSON return
- [ ] :app models off JSON (Mapping, CalendarMapping, push payloads, mail pages, queue actions)
- [ ] params records for wide signatures

## Phase 6: tests

- [ ] MailEngine final, test forwards gone, tests fake the domain clients
- [ ] ZonesTest, MailFillTest, CalendarMappingTest off Robolectric

## Phase 7: docs

- [ ] package-info.java per package, root one the architecture document
- [ ] Javadoc and comments to the java scope
- [ ] docs/ refreshed

## Phase 8 candidates (separate change)

Logic found in Java that belongs to Rust, collected while moving.

## Notes from phase 1 (inputs to phases 2 to 6)

- pimdir: MailStore.typed/changed are generic typed-binding plumbing (FileStore 11 calls, ItemLinks, DeletedItems, PimdirItems, PimdirDb): move to pimdir. CacheSweep (MainActivity only, sweeps mail scratch and staged files) is app lifecycle. DeletedItems restores through PimdirEngine and MailEngine: above sync. ItemLinks' search half is domain-level. DefaultCollection keeps a prefs choice and reads AccountActivation: account policy. PimdirCollections filters MailStore.ALL_MAIL. Back edges: PimdirDb -> FileStore, MailStore; PimdirItems, PimdirCollections -> MailStore; PimdirSummary -> MailDate; DeletedItems -> MailEngine, MailStore, PimdirEngine; ItemLinks -> EventStore, FileStore, MailStore; CacheSweep -> FilesList, MailEngine; DefaultCollection -> AccountActivation.
- public for tests (phase 6 removes): PimdirDb ctor, PimdirItems.Row + put, PimdirBlobs.has/put, ItemLinks.RELATED/Endpoint ctor/placement, DefaultCollection.of(List,String), CacheSweep.AGE, PimdirStorage.sourceOf (E2eDevice), CardStore.shared. Tests left in org.pimalaya for internals: DeletedItemsTest, ItemLinksTest, DefaultCollectionTest, BodyLossRacesTest, AutomaticReferencesTest.
- sync: RemotePass and SyncRunner reach into every domain (16 and 11 root classes): the phase 3 SyncCoordinator. PimdirEngine is the base of three engines in other packages, so its mutate*/offline/result/clock are public. TokenRenewal is credential plumbing (account). MergedFilter is a persisted view filter used by list screens (ui or per-domain). SyncSteps formats R strings for MainActivity only (ui). Public for tests: AccountEntry.of, AccountCredential.oauth, PimdirEngine.mutateCopy, PimdirEngine.Clock + listed, RemotePass.MailPass.sent (e2e), FakeAndroidKeyStore.install. Tests left in root: BackgroundCheckTest, MergedFilterTest.
- phone: PhoneQueue.start builds OfflineEngine and calls CalendarEngine.phonePass while both engines call PhoneQueue.written: a cycle; its ctor already takes Consumer passes, so the wiring of start() moves to the root. SyncRunner -> PhoneMirror is a sync -> phone back edge.
- file: FilesList.STAGED read by pimdir/CacheSweep (belongs in FileStore); PimdirDb -> FileStore.reconcileRoles on open. FilesList and FileActions reach into MainActivity fields (io, files, mail, postAlive, dp...): screens need a host object, not the activity. Public for tests: MailStore.forget/accountIdOf/saveSource (FileStoreTest), FileStore.partKey/attachmentsOf (AutomaticReferencesTest).
- calendar: EventStore and EventView call CardStore.rowHandle (store plumbing, pimdir). CalendarList and EventView reach into ~20 MainActivity members. Public for tests (e2e): Zones + utc, EventStore.StoredCalendar.name, StoredEvent.handle/ical. DefaultCollectionTest lives in calendar (EventStore fixture). E2eCalendarTest stays in root with the harness.
- contact: LocalBook ("On this device") is cross-domain (FileStore, FilesList, BackgroundCheck): account or pimdir. Birthdays is contact (ContactsList only). Contact screens reach into ~25 MainActivity members (openGroup, mergeReplicas, openMerged, importContacts...: the phase 4 ContactEditor). Public for tests: BookEntry ctor (AccountsTest), PimdirContacts.Indexed + list (DeletedItemsTest, e2e).
- mail: MailStore.typed/changed/likePattern are pimdir plumbing (ItemLinks, DeletedItems, FileStore 8 calls); MailDate.toStamp used by PimdirSummary; MailEngine.SCRATCH, withFlag, DELETED pulled into pimdir. MailBodies/MailFill scheduling driven from MainActivity: SyncCoordinator. E2e mail tests stay in root with the harness (about 50 harness members otherwise). Public for tests: MailEngine.SEEN/FLAGGED/Deletion/stageFlag/stageDelete, MailStore.trashOf/holdsBody/source/storedSource/restateFromBody/StoredMessage ctor + seen/flagged/hasAttachment/objectHash, MailWindow.since, MailReader + Read, MailSubmit, MailPoison, MailOwed.key, MailBodies.ATTEMPTS, MessageView.linkIntent. res/layout/panel_compose.xml inflates org.pimalaya.mail.RecipientField (device check: compose screen opens).
- account: two layers in one package. PimDomain, AccountEntry, AccountCredential, SecureStore, AccountActivation, Accounts are vocabulary and credential plumbing used by pimdir, sync, phone and every domain (the Rust `account` is likewise cross-cutting and low); OnboardingFlow, OauthFlow, AccountSettings are screens over every domain (~30 MainActivity members widened). Phase 2: account sits low (below sync), the setup and settings screens move to a package above the domains (decide its name then). Public for tests: AccountEntry.empty/connection/with, AccountConnection + baseUrl, OnboardingFlow.submissionUrl/asUser/endpointUrl (E2eTarget), FakeAndroidKeyStore.KEYS.
- ui: Ui, Sections, Chips, PillField, ListHeader, CardSections, Avatar, Dates, SyncDock are shared widgets (they import only R, MainActivity, Domains). FilterPage (account, mail, pimdir, sync), DeletedItemsPage (account, client, contact, pimdir, sync) and LinkedSection (contact, mail, pimdir) are cross-domain pages driven by MainActivity (43 to 62 `host.` uses each): they go above the domains with the setup screens, not in ui.
- shell: org.pimalaya holds exactly the nine components and app classes.
- end of phase 1: LayeringTest run unignored reports 64 file-level violations (sync -> account 9, pimdir -> mail 7, contact -> account 7, mail -> account 6, ui -> root 7, contact -> root 6, ...). Most -> account edges vanish once account's low half sits below sync.
- phase 2 step 6: PhoneQueue.of tolerates a context whose application is not an Owner (e2e harness E2eDevice.Isolated answers itself as application context): no-op, as before when the queue was not started.
- phase 2 steps 7-8, phase 7 doc fixes owed: PimdirDb.reconcileRoles Javadoc says "this class files them" (FileStore files them); PimdirStorage.rowHandle links CardStore's package-private resourceName; PimdirEngine.Progress links SyncSteps and MailPool (unresolved). PimdirDb.setRole is public (FileStore.recordAttachments also calls it); PimdirEngine.has public (MailStore and tests call it through MailEngine).

## Device smoke tests owed (collected; run once, on a debug build)

- Cold launch (App registered); onboarding with password and OAuth browser grant (loopback redirect); account settings open, delete an account (its phone book accounts go).
- Mail: first sync with the list filling live; a failed or gone body on its row; compose opens (RecipientField inflated from mail); Gmail All Mail hidden; search with % or _; restore a deleted mail clears \Deleted; message view date.
- OAuth: a 401 renews the token for mail, contacts and calendar.
- Phone mirror: a contact edit reaches Contacts within a second; a Contacts-app edit comes back; same both ways for calendars; phone book accounts created and named as before after a sync and after account settings changes.
- Background: background sync or notifications turned on schedules the job; a background check while the app is closed skips while on screen, reloads lists on return; a background run refused while the app syncs and the reverse; account settings show "sync busy" while syncing.
- Time zone change reprojects floating events. New event and contact edit or merge under a provisional handle.
- Filter page (and a background run honouring it); deleted items restore mail and contact, free space; links dialog lists messages first, clearing empties it; files open, share, export (also one whose message is gone), "show message"; any file opened through the content provider.
- Old store gets its attachments role backfilled; a first attachment creates the Attachments collection.
- List header titles, bar title fade, FAB fold on scroll; sync dock, chips, pill fields, error dialog and toast.
- phase 3 steps 1-4: MailReader stays on MailEngine (MainActivity.mailEngine(email) serves only the reader) because StorePerformanceTest fakes the reader's source read by subclassing MailEngine: phase 6 moves that fake to the mail client, then MailReader takes a MailStager. MailMoveTest 429 stays on CalendarEngine (it asserts push's relocation refusal). Phase 7 doc fixes: MailStore 394 (MailEngine.stageDelete), ContactEngineServeTest and MailEngineTest setUp comments, MainActivity.mailEngine Javadoc.
