---
cairn: tasks
change: mail-download-window
---

# Tasks

Java paths are relative to android/app/src/main/java/org/pimalaya/, tests to android/app/src/test/java/org/pimalaya/, resources to android/app/src/main/res/.

## Store

- [x] Wait for the pimdir change (`:since` on `list_mail_page_filtered`, `count_mail`, `count_mail_by_day`, `count_unread`; new `sum_mail`) and its io-pimdir patch release; read the released statements' parameter and column names from io-pimdir, never from memory
- [x] rust/Cargo.toml: raise both `io-pimdir` requirements (dependency and dev-dependency) to that release; refresh Cargo.lock. No rust/src change: `Native.pimdirSql` (rust/src/ffi/pimdir.rs) already hands Java every statement of `io_pimdir::sql::all()`
- [x] `MailStore.Query`: `floor` becomes `since`, bound as `:since` in `values()`; the builder `reaching(floor)` becomes `since(date)`; `unread()` keeps it
- [x] `MailStore.count`, `countByDay`, `page`, `listed`: the canonical statement wherever nothing is searched and nothing hidden, the date bound inside it; drop the private `reaching(Bound, floor)` wrapper (`hiding` and the search wrappers stay, search ignoring the floor)
- [x] `MailStore.unread(Query)`: binds the query's `:since`, so the badge counts one window per call
- [x] `MailStore.sum(Query, since, until)`: `sum_mail` over the query's collections and chips, answering count, summed size and the count of unknown sizes
- [x] `MailStore.newestBelow(Query, floor)`: one row of `list_mail_page_filtered` keyed after a cursor sorting before every row dated at the floor, null when none
- [x] `MailStore.floorOf(Query, windows)`: the list floor, max of the coverage limits of the query's collections (today's `floorOf`) and the windows of the accounts holding a queried mailbox not kept whole; the max itself a static helper, tested pure
- [x] `MailStore.StoredMessage`: carries whether its body is held (`object_hash` set and `level` at `Full`), its size (null unknown) and its attachment mark (1, 0, null), from the page's columns; `MailStore.holdsBody` stays for the delete question
- [x] `MailStore.bodyRows(ids, since)`: binds `:since` (null for a mailbox kept whole); `MailBodies.Row` gets size and attachment mark
- [x] `MailStore.bound`: a narrower bound also raises the window (`MailWindow.raise`); `MailStore.forget` (line 173) forgets the window too

## State

- [x] New `MailWindow` (prefs `mail-window`, keyed by account id): `since(context, accountId)`, the stored date clamped to the bound's floor (`MailScope.clamp`), null for all mail; `set` (never later than the stored one), `setAll`, `raise(floor)`, `forget`; whether one is stored at all
- [x] `MailWindow.initial`, pure: inbox coverage floor; for an account-wide account or one with no inbox, the most recent coverage floor among its mailboxes (read from `MailStore.edges()` and `coverage`, not from the engine's private `accountFloor`); all mail when no mailbox has a floor; first of the current month when the account holds no mail
- [x] `MailWindow.next`, pure: the first of the month of `newestBelow`'s date; else, when a shown mailbox still has headers to list below the floor (`MailScope.limits` on its coverage), the latest first of a month before the floor; else none. Reuses `MailScope.since`'s first-of-month arithmetic
- [x] `MailWindow.migrate(context, store)`: once, for every mail account past its first mail sync and holding no window: the bound's floor where the old policy was `BACKGROUND` or `WHOLE`, else the date of the 50th newest stored inbox row (fewer: as `initial`); then drop the old keys
- [x] `MailScope`: `sinceOf(context, accountId)` no longer reads the policy; add `covering(date, today)`, the smallest `MONTHS` choice whose floor is at or before a date, 0 for none
- [x] `MailOffline`: drop `Policy`, `policy`/`setPolicy`, `metered`/`setMetered`, `downloads`, `downloadsAny`; keep `whole`/`setWhole`/`forget`; add the migration's read of the old `policy:` key and its removal with the `metered:` ones; class doc rewritten
- [x] `BackgroundCheck`: the newest notified sort key per account (read, write, forgotten with the account)

## Engine and fill

- [x] `MailEngine.widen(collection, count, stop)`: a chunk of the fill never listed below a stop date (replacing a one-shot `reach`, so an open waits one chunk at most); a mailbox never listed or filling synced as `widen` does
- [x] `MailFill.limiting`: drop (only `widenMail` used it)
- [x] `MailBodies`: `wanted(newestFirst)` keeps only the not-held filter (the window is the statement's `:since` now); `CAP` (256 KB) and `fits(row, metered)` (size known and at most the cap, or unknown with attachment mark 0); drop `networkAllows`; `Host.metered()`; on a metered network `step` takes fitting rows and leaves the others in the plan, an account holding only those answering as paused; class doc rewritten

## MainActivity

- [x] `planBodies` (MainActivity.java:1990): every enabled account, no policy gate; per mailbox: kept whole, all its rows (`bodyRows(ids, null)`); junk or trash role, none; any other, its rows since `MailWindow.since`; the shown mailboxes' rows first, each group merged newest first
- [x] `bodiesAllowed`: foreground, no sync, account on, a network; `bodyHost().metered()` from `ConnectivityManager.isActiveNetworkMetered`
- [x] `fillStep`: the body step first; the fill step only once the body step answers `DONE` or `PAUSED`
- [x] `firstMail` (MainActivity.java:1784): after `FirstSync.paid`, store `MailWindow.initial` for the account
- [x] `onCreate`: `MailWindow.migrate` on the io thread before the first `fillMail`
- [x] Drop `widenMail` (MainActivity.java:2225) and both `mailList.retryOlder()` calls (lines 850, 1632)
- [x] New `moveWindow(accounts, date, shown, done)`: widen each account's bound where the date is below it (`MailScope.covering`, through `MailStore.bound`), noting it for the confirmation; `MailWindow.set` (or `setAll`); reload at once; online, `reachStep` widens every shown mailbox whose coverage floor is above the date by a chunk, never below it, one io task per chunk, stopping on a sync; then `fillMail()` (replans the bodies) and the list's reload; a toast when a period widened

## MailList

- [x] `reload` (MailList.java:270): the windows of the shown accounts; `floor = store.floorOf(wanted, windows)` unless searching; `listed = wanted.since(floor)`; the badge summed over the filter's accounts, one `unread` each at its window (MailList.java:308); the footer's state: `MailWindow.next`, `store.sum` between it and the floor, whether headers are still to list, whether a shown account is bounded
- [x] Footer row replacing the *more* row: inflate `item_mail_window`, the button calling `host.moveWindow`, the info line (count and size per the proposal's wording; *size unknown for N*; *older headers still syncing*; *waits for the network*; *larger ones on Wi-Fi* past 10 MB on a metered network), *Choose a date*; hidden under a search; drop `older`, `more`, `stallOf`, `retryOlder`, `widening`, `stalled`, `Layout.limited`
- [x] `render`: the empty state only when the footer does not show (MailList.java:356)
- [x] Row binding: dimmed when its body is not held; *Downloads on Wi-Fi* when within its window, not junk or trash, metered and not fitting; *Not downloaded* below its window or in a junk or trash mailbox not kept whole; the layout carries the windows, roles and the metered state the binding reads
- [x] New `WindowPicker`: the date picker dialog with *All my mail*, previewing the info line for the date picked (`store.sum` on the io thread), confirming through `moveWindow`; used by the footer and the settings row

## Pages

- [x] `AccountSettings` (lines 218-271): drop the policy row, the metered switch, `setPolicy`, and the period's dimming under *whole*; add the window row (*Mail on this phone since 1 September*, *All my mail*) opening `WindowPicker` for this account, dates before its window only
- [x] `FilterPage`: unchanged behaviour; its confirmation string loses the metered allowance
- [x] `MessageView.load` (MessageView.java:138): no body held and no network: the not-on-the-phone state, worded by whether the message falls within its account's window outside junk and trash, and no fetch
- [x] `BackgroundJob`: `unreadInbox` (BackgroundJob.java:185) reads the inbox since the window; the arrived messages filtered after the newest notified sort key, a static helper so it is tested; the newest notified one stored after `notify`

## Resources

- [x] New layout `item_mail_window.xml` (button, info line, secondary action); delete `item_mail_more.xml`
- [x] Strings, values/ and values-fr/: *Load since %s*; the info line plurals (messages with size, with size unknown for N, size unknown); *older headers still syncing*; *waits for the network*; *larger ones on Wi-Fi*; *Choose a date*; *All my mail*; *Not downloaded*; *Downloads on Wi-Fi*; the two offline-open wordings; the settings row (*Mail on this phone since %s*); the period-widened confirmation
- [x] Remove `mail_more_loading`, `mail_more_offline`, `mail_more_failed`, `mail_offline_title`, `mail_offline_message`, `mail_offline_policies`, `mail_offline_metered`, `mail_offline_metered_note` and the comment at values/strings.xml:127, in both locales; reword `filter_download_message` in both
- [x] values-en-rGB: check nothing there names a removed string

## Tests

- [x] Drop `MailOlderTest`, and `MailFillTest.aScrollWidensEveryShownMailboxHoldingTheLimitingFloor`
- [x] Rewrite `MailChunksTest.theListReachesDownToTheMostRecentFloorAndWidensTheMailboxHoldingIt`: the list stops at the merged floor, and a stopped widening lists the band down to a date a chunk at a time, the band alone
- [x] Rewrite `MailBodiesTest.theStepRaisesOnlyBodiesWithinTheBound` (within the window), `aMailboxKeptWholeDownloadsPastTheBound` (past the window, trash included), `aMeteredNetworkDefersIt` (the cap: small downloads, large waits, unknown size with mark 0 downloads, unknown with mark 1 or null waits)
- [x] Rewrite `MailStoreTest.theListIsSizedPlacedAndPagedByTheStore` (count, days and pages under `:since`; `sum` with unknown sizes) and `aNarrowedBoundCollectsWhatFallsBelowIt` (the window raised)
- [x] New `MailWindowTest`: initial value per backend shape (inbox, account-wide, no inbox, all fitting, empty); migration per old policy; period interplay (picked below the bound widens it to the smallest choice, narrowing raises); next date with an empty month and with nothing stored but headers to list; the merged floor with a mailbox kept whole
- [x] Body planning: roles excluded unless kept whole, the window, the shown mailboxes first
- [x] Badge windowing (MailStoreTest): unread below a window not counted
- [x] Notification cutoff: below the window and at or before the newest notified not counted

## Review fixes (2026-10-10)

- [x] `MailWindow.begin` keeps a window already held (an account set up again re-runs `firstMail`)
- [x] Notification mark kept no later than the run (`BackgroundJob.watermark`), cleared by `BackgroundCheck.setNotifies` and seeded by the next run from the newest unread inbox message
- [x] `moveWindow`: reload at once, the band a chunk at a time (`reachStep`), stopped by `syncing`, accounts that are off skipped
- [x] `MessageView.load` reloads the list after storing a fetched body
- [x] Settings picker over the filter's mailboxes; the info line counts only downloading mailboxes (`MailStore.downloading`, `MailStore.listing`) in the footer and the settings alike
- [x] French `mail_row_wifi`: *Se télécharge en Wi-Fi*
- [x] Window dates on the device's clock (`MailWindow.startOf`, `dayOf`; `next` and `initial` take the zone; picker and label too)
- [x] An empty list with nothing older stored shows its empty state alone, not a lone *Choose a date*
- [x] Migration runs on the main thread in `onCreate`, before the first list read
- [x] Delta: the small-inbox rule of `firstWindow`
- [x] Tests: `MailWindowTest.aDateIsMidnightOnTheDeviceClock`, the watermark in `BackgroundCheckTest`, the chunked band in `MailChunksTest`

## Moving the window later (plan B step 4)

- [x] rust/Cargo.toml: io-pimdir patch rev 1030bfc (release statements, `item_reference`); Cargo.lock
- [x] `item_reference` table, index and trigger reach an existing store through `PimdirDb.reconcileDraftShape` unchanged; `PimdirDbTest.aStoreWrittenBeforeReferencesGainsThemOnOpen`
- [x] `MailStore.release(account, until)`: `RELEASE_BASES_BEFORE`, `RELEASE_BEFORE`, `RECOMPUTE_REFCOUNTS`, collector, one transaction, mailboxes kept whole skipped
- [x] `MainActivity.releaseWindow`; `WindowPicker.open(..., later)`: the settings row offers days up to today, a later one confirmed (`mail_window_release_*`, both locales) then released
- [x] Delta: "An account's settings show its window" allows later; ADDED "A window moved later frees the bodies below it"; proposal and CHANGELOG
- [x] Test: `MailBodiesTest.aWindowMovedLaterFreesTheBodiesBelowIt`

## Land

- [x] CHANGELOG.md, net diff of [Unreleased]: rewrite the *offline mail per account* bullet into the window; in the *whole mailboxes* bullet, the badge and select-all follow the window, search every stored header; in the *short first sync* bullet, the footer replaces scrolling to the end; drop *the end of the list* from the *parallel mail sync* and *band-by-band loading* bullets
- [x] Build: `:app:assembleDebug` and `:app:testDebugUnitTest`; cairn/verify.sh
- [ ] Device test (the user): a fresh account of each backend (window set, bodies then fill, badge), the footer over two accounts, an empty month, offline tap, metered cap, search beyond the window, offline open, settings row, a tester install migrating
- [ ] After the device test, and once `account-settings-page`, `background-check` and `onboarding-options-ask-on-switch` are folded: fold the delta into spec/mail.md and spec/offline-store.md (with the intro edits it states, and the wording of those changes' requirements as the proposal says), write cairn/log/YYYY-MM-DD-mail-download-window.md, set `status: landed`, move to changes/archive/

## Notes

- io-pimdir is taken from a git rev through `[patch.crates-io]` until its patch release; the pin moves to the release then.
- `MailStore.floorOf(query, edges)` takes the edges the list reads once for the floor, the footer and the windows.
- The body planner's rules are `MailBodies.downloads(role, whole)` and `MailBodies.takes(sortKey, window, role, whole)`, the latter shared by the row labels and the reader's offline wording; `MailBodies.merged` keeps a planned group newest first across windowed and whole mailboxes.
- `MainActivity.widenAll` became `listAll(edges, step, failed)`, run by the fill (`widen`) and a moved window (`widen` with a stop date).
- `MailOffline` keeps `whole` plus `downloadedAhead`/`forgetPolicy` for the migration.
- Tests: the notification cutoff sits in `BackgroundCheckTest`, the migration in `MailStoreTest`, body planning in `MailBodiesTest`, the whole-mailbox floor in `MailChunksTest`; `MailWindowTest` covers the initial value, moves, the bound interplay and the next date.
