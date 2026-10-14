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
- [ ] sync
- [ ] phone
- [ ] file
- [ ] calendar
- [ ] contact
- [ ] mail
- [ ] account
- [ ] ui
- [ ] shell (org.pimalaya: components, composition root)
- [ ] OfflineEngine renamed ContactEngine

## Phase 2: dependency direction

- [ ] cycles of finding 4 broken (role labels, migration hooks, stager handed in, mail flags type, token expiry as an exception, phone pass handed in)
- [ ] one AppState instead of UI/service static flags; mutable static sets owned
- [ ] one lock registry
- [ ] dependency test green

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
