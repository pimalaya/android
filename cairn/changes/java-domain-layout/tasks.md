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

- [x] stager split from remote pass: pimdir.Stager + ContactStager, CalendarStager, MailStager; phone-only role as PhonePass (ContactPhonePass, CalendarPhonePass) over pimdir.PhoneSpoke; server engines refuse phone collections
- [x] sync.WorkerPool (lone worker inline for mail too); ContactEngine.pushAll keeps its pool
- [x] sync.SyncPasses (SyncRunner + RemotePass, headless), BackgroundCheck.run, sync.SyncProgress (dock model), sync.SyncCoordinator (foreground owner; MainActivity 4207 -> 3161 lines); its posts all through postAlive
- [x] one phone-spoke contract (pimdir.PhoneSpoke, PhonePass)
- [x] device smoke tests: added to the owed list below

## Phase 4: logic out of the UI

- [x] setup: SetupPlan (+ SetupOption, SubmitOption), AccountCommit, Endpoints URL helpers; UI-only posts guarded, data-persisting posts kept
- [x] mail: BodyPlan, MailSubmit.Draft + marksAnswered, MailReply (all tested)
- [x] contact: ContactEditor + ContactMerge (tested); composeNew/openStored in EventView
- [x] calendar: EventWrites (tested)
- [x] setup: AccountRemoval (tested)
- [x] filter: Filters (tested)
- [x] ui: SectionsAdapter (contacts, agenda, files), CardRows, one dp/resolveColor (RecipientField keeps rounding), Background (adopted where exact), Dates for the listed sites (pinned by tests); every screen off MainActivity, TOLERATED deleted
- [ ] MailStore split deferred to phase 5 (same StoredMessage seam), PimdirStorage split to phase 8
- [x] device smoke tests: added to the owed list

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
- phase 3 step 8, recorded behaviour change: WorkerPool runs a lone worker on the calling thread for mail too (calendar did already), so an interrupt of the caller (activity destroyed) reaches the inline work instead of becoming an "unopened" failure. ContactEngine.pushAll keeps its own fail-fast pool. Worker warnings now read "worker could not connect: <label>" / "worker failed: <label>", the label carrying the domain and account.
- phase 3 steps 9-10: SyncRunner + RemotePass are sync.SyncPasses (Parts, the app's first record); BackgroundCheck.run takes a notify callback (not a returned map) so new-mail notifications still fire right after the mail pass. Phase 7 tidy-ups: dead `progress != null` checks in SyncPasses, inline java.util/android.net qualifiers, "this runner" wording. Phase 8: collectionsOf filters subscribed books in Java.
- phase 3 steps 11-12: SyncProgress (dock model) and the SyncCoordinator skeleton; sync -> ui allowed (the coordinator draws the dock and posts through the host). SyncProgressTest reads SyncDock's private pass by reflection (phase 6: reconsider). Members public only for MainActivity's unmoved code, narrow after step 15: SyncProgress.resetSyncPlan/planSync/planSteps/syncAccount/started/fill, SyncCoordinator.startSync/setSyncing/observe/reportSync/progress. The sheet lists reached sections first in reach order, then the rest in plan order.
- phase 3 step 16, deliberate change: the coordinator's and the dock model's eight `main.post` calls (first-sync after a pass, landing after connect, Wi-Fi back, dock renders and log lines) now go through `postAlive`, dropped once the activity is destroyed. Screens still post unguarded in about 15 places (Birthdays, MailList, VcfTransfer, OauthFlow...): phase 4's background-work helper. Owed: SyncCoordinator.backgroundShown (showBackgroundDock still in MainActivity); MainActivity dead privates booksOutside, hostOf, openAdvanced (phase 4/7).
- Phase 3 additions: drawer sync over all domains (dock lines, rows reached first then to come, bar never back, one error dialog); pull to refresh per list, mobile data mail pull waits for Wi-Fi and runs when it returns (also after background then foreground, once); connect an account lands on its first tab and first-syncs, a tab opened during a pass syncs after it; ticking a never-listed mailbox runs its first chunk; background fill and body download with drawer pills counting down, leaving the app mid-fill, Load more and window release; a one-mailbox account pull; Gmail pass; calendar with several calendars; phone switch on shows the projection on the dock, off removes the account; contact conflict made on both sides auto-resolves; flag, delete, send mail; edit, split, delete events; edit, merge, auto-resolve contacts; restore mail, contact, event.
- phase 4 steps 1-3, deliberate change: 18 screen posts now go through postAlive (UI-only outcomes); 5 stay on main.post because they may persist the account or its books (OnboardingFlow signedIn, verified, books connected; OauthFlow scheme and loopback redeems). OauthFlow's scheme-grant launch (pending PKCE state) and MailList's selection actions are guarded: nothing they would write can succeed once the activity's io is shut down.
- phase 4 steps 8-9: ContactEditor (+ ContactMerge, tested) out of MainActivity; the six contact screens off MainActivity. Found dead, not deleted (user decision): the advanced/source panel is unreachable (contact_advanced button never made visible, AdvancedEditor.open has no caller), EditSession.advancedAvailable written never read. ContactParts.accountFor duplicates MainActivity.accountFor until step 16. Phase 7 doc fixes: VcfTransfer and ContactWriter class Javadocs.
- phase 4 step 10: EventWrites (tested) out of EventView; openStored/composeNew into EventView; calendar screens off MainActivity (CalendarParts). Tidy-up owed: the calendar collections listing is written out in SyncPasses.collectionsOf, CalendarList.reload and EventView.composeNew (one EventStore method could serve all three).
- phase 4 steps 11-13: MailReply, MailSubmit.Draft + marksAnswered, BodyPlan (all tested, draft JSON keys and order unchanged); MailSync (coordinator) and MailParts; mail screens off MainActivity; coordinator built before the screens. MainActivity.sync temporarily public for AccountSettings' WindowPicker (narrow in step 16).
- SUSPECTED PRE-EXISTING BUG (not fixed, behaviour-preserving change; predates this change since 6f15f41): with two or more mail accounts the body plan (BodyPlan.of, formerly planBodies) loops over mail.loadMailboxes(List.of(email)), which returns every account's mailboxes (collections.list(MAIL) unfiltered), and only filters by the looping account's flag and the collection's tick, not by the collection's owner. Account B's mailboxes are then downloaded under account A's session and account id. Needs a device check with two mail accounts and a fix of its own (filter by stored.accountEmail).
- phase 4 steps 16-17: setup screens off MainActivity (SetupShell), trash and link off it (LinkShell); TOLERATED deleted from LayeringTest; Panels aliases gone; MainActivity 2223 lines, public only for overrides (Activity, Host, Screens, SetupShell, LinkShell) and `client` (PendingGrantTest). AccountEntry.find is the one account lookup. Dead, not deleted: MainActivity.leaveOnboarding. Phase 7 doc fixes: AccountSettings class Javadoc, PhoneMirror's link to MainActivity#askMirrors. Phase 8: DeletedItemsPage.appends (backend capability), AccountSettings.serverHost/protocolOf (scheme classification).
- phase 4 step 19: ui.Background adopted only where a site matched it exactly (VcfTransfer import and export). The ~45 other io sites differ: no catch (an unchecked error crashes today; the helper would route it to failed), a Log.w in the catch (moved behind postAlive it would vanish after a rotation), shared spinner resets, finally blocks, kept main.post, other executors. Left as they are on purpose.
- phase 4 step 20: Dates.time/weekday/dayOfMonth/month/year/dayMonth/dateTime, DeletedItems.stamp, ContactForm/Sections dp forwards gone, ui.CardRows for OnboardingFlow (accountCard differs, kept). Phase 8: DeletedItemsPage parses retainedAt (store format in the UI).
- Phase 4 device additions: cold launch; onboarding standard and advanced (books, phone switches, notifications, OAuth scheme and loopback, a redirect after process death, rotation mid-flow, back at every step); account settings switches, window picker, delete account; contacts: open/edit/save merged, conflict, merge selection, manage books, new contact picker, vCard import/export, birthdays, duplicates, selection bar; agenda tap, pull, Default chip, edit a series (this/following/all), delete occurrence/all, new event picker, linked card; mail list pull, load more, selection actions, reader (attachments, linked, star, delete, send again), reply/reply all/forward (answered on IMAP/JMAP, not Gmail/Graph), compose offline; files open/share/export/import (also with rotation), folders, show message; filter page per domain, Apply/Reset; deleted items restore and free space; links open message/contact/event/file; lists scroll and header taps (contacts enabled, agenda not, file folders yes); dates in mail rows, agenda strip and headers, deleted items, birthdays.
- phase 5 B1-B1b: offline records (SyncReport, UpgradeReport, facts, PushPlan, PushAction, Mutation) and Flags (Marker enum) + Derived, pinned on the real bridge; strict PushAction (recorded). Recorded JSON exception: Flags.read(JSONArray) on the offline-driver wire. User decision 2026-10-14: delete the unreachable advanced/source contact editor (AdvancedEditor and its six verbs).
- advanced editor deleted (user decision): AdvancedEditor, panels advanced and source (Panels renumbered, no persisted index), ContactClient cardProps/cardSetProp/cardSetPropParts/cardPropLabels/cardSource; CHANGELOG [Unreleased] Added line rewritten as net diff. cardTypeOrder kept (TypeTablesTest only). Phase 8: drop the five natives and rust/src/contact/props.rs exports.
- phase 5 B2: calendar records (EventTime, Calendar, Event, EventRef, EventDelta, Occurrence, EventDetail, EventSplit, EventMerge) + strict enums EventTimeKind, EventSide, EventConflictKind (recorded) + params EventTarget, EventDraft, EventSides; values captured before/after identical. DAV verbs pinned on literal fixtures (the bridge refuses credentials over plain http, a loopback DAV fixture would need a trusted self-signed cert). Phase 8: CalendarRemote.projected sniffs printed JSON for floating times, CalendarRemote.zones walks it for TZIDs.
- phase 5 B3: EventEdit (+ Field, DateField), DateEdit, EventScope; EventView's edit model typed; same edit JSON and same iCalendar from the bridge for 19 captured page states. Phase 8: EventView.edited computes the minimal edit in Java (diff against the opened page, zone attach): Rust could diff the form state.
