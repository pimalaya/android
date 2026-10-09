---
cairn: tasks
change: phone-contacts-mirror
---

# Tasks

- [x] Onboarding: the switch on the Contacts card, standard and advanced; `commitBooks` takes it
- [x] Permission asked on continue and on the settings switch only; refused turns the switch off
- [x] Detection of the same address under another account type; result page and settings line
- [x] Debounced phone pass after every write to a mirrored book
- [x] `setSyncAutomatically(true)` on reconcile, for new and existing accounts
- [x] Phone pass on return for changed books
- [x] Drop `runner.syncLocal` from the pull and the drawer's sync, the Local report line, the adb hook
- [x] Switch on: reconcile and first projection on the strip; off: last pass, then removal
- [x] Build: `:app:assembleDebug` and `:app:testDebugUnitTest`
- [ ] Device test: edit in the Contacts app with Pimalaya closed, then open it; edit in Pimalaya, then open the Contacts app; a Gmail address signed in on the phone
- [ ] Fold the delta into the spec, log, CHANGELOG

## Notes

- **The switch.** Standard: a row under the Contacts row of the domain card, shown while contacts are ticked. Advanced: the last row of the Contacts card, shown while the domain is switched on; it applies to every book ticked on the books page. One state for both (`OnboardingFlow.phoneContacts`), kept when the standard setup hands over to the advanced one.
- **The permission prompt** goes through `MainActivity.askMirrors(Set<PhoneMirror>, callback)`: `PhoneMirror` is an enum of the phone apps the store shows in (`CONTACTS` today, its two permissions), so the calendar mirror adds `CALENDAR` and one `wanted.add` in `confirmSetup`, and both pairs are asked in one prompt. A mirror counts as granted only with all its permissions.
- **Detection** (`Accounts.elsewhere`): one `RawContacts` query, `ACCOUNT_NAME IN (address, lowercase address) AND ACCOUNT_TYPE != org.pimalaya AND DELETED = 0`, first row only. It runs in the io task that lists the books once signed in (the permission was granted at Continue), rather than right after the grant: same single query, and what it found is said on the page that follows. Our own accounts never match, being named by the book.
- **Where it is said.** Standard: the result step in a second shape ("Connected", a row per connected domain, Contacts reading "Already in the phone's contacts through another app", the note, Continue), shown before the books are committed, so Back drops the sign-in as the result step does. Advanced: a note on the books page. Settings: a line leading the Addressbooks card, looked for as the page opens (off the main thread). The per-book "Show in the phone's contacts" box still forces mirroring.
- **The debounced pass runs on its own thread, not the activity's io executor** (`PhoneQueue`, process-wide, started in `onCreate`). The io executor is shut down in `onDestroy`, so a write made just before backing out of the app would have lost its pass. The pass takes the book's lock only, never `SyncLock`, and reports nothing. Fixed window: the first write of a burst times the pass a second later; writes landing before the pass drains its set join it. The hook is `PimdirEngine.staged`, called after every staged mutation (`OfflineEngine` queues non-phone collections), plus the two direct writes of `PimdirContacts` (`stageMembership`, `detachToLocal`).
- **The pass on return** runs each mirrored book's quiet path (`PhoneRemote.changed`, then the store's pending check and the member count when the provider count is clean) rather than `PhoneRemote.changed` alone, so a store write whose queued pass was lost to a killed process is also projected. It reloads the contacts list when it, or an upload sync run by `SyncService` meanwhile (`SyncService.ingested`), brought an edit in.
- **`setSyncAutomatically(true)` once per account**, marked in the account's user data, so accounts from earlier builds are turned on at the next startup and a later choice in the system's settings is not reset by every reconcile.
- **Reconcile** runs at startup, when a setup commits its books (that is the setup's switch), when a book's switch turns on, and on account removal. A switch turned off runs one last pass and removes that book's account (`Accounts.remove`), without a reconcile. Account removal keeps its explicit removals and reconcile, with no last pass.
- **Settings.** Turning a book on brings it to the phone too, as before, unless another app has the address; it asks the permission when it does, and a refusal leaves the book on and off the phone.
- **The background run** keeps its offline phone pass (`SyncRunner.syncPhone`, formerly `syncLocal`, now without the reconcile), for every mirrored book.
- **Removed:** the in-app `syncLocal()`, its adb extra, the pull-time and commit-time permission requests (`ensureContactsPermission`, `hasContactsPermission`), the Local toast and `SyncRunner.Outcome`'s local fields, and the strings `sync_line_local`, `accounts_failed`, `sync_local_requested` (already unused).
- **Tests:** `PhoneMirrorTest` (what a prompt asks, what counts as granted), `PhoneQueueTest` (a burst is one pass per book; a write while the pass waits joins it; a later write starts the next round), `AccountsTest` (a new account syncs automatically; an earlier build's account is turned on; a choice made in the system settings stands). The detection is one provider query with no logic around it to test on the JVM.
