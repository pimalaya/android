---
cairn: tasks
change: phone-calendar-mirror
---

# Tasks

## Phase 0
- [x] Research agents: DAVx5 (synctools, ical4android), AOSP CalendarProvider and Etar, ICSx⁵, Fossify Calendar; revise the contract from their findings
- [x] Settle `docs/calendar-mapping.md` (delegated to the supervisor, 2026-10-09)

## Phase 1: Rust
- [x] `projectEvent`: master, overrides, rule set, alarms (end-related, absolute, repeating converted), attendees, zones; objects without a master as standalone overrides
- [x] Series the provider cannot show projected as an `RDATE` instance list over the window (`IcalRecurSet`)
- [x] `applyEvent`: the managed set patched through the CST; overrides added, changed, removed, cancelled; `DTSTAMP` refreshed, `SEQUENCE` bumped for organized group events
- [x] Three-way merge through `IcalMerge`, conflicts named by field: a phone pass runs `calendar-conflict-form`'s `CalendarEngine.triage` on the phone source
- [x] Tests: round trip byte-identical for an untouched model; each field; overrides; zones; alarms; instance lists

## Phase 2: Java
- [x] `CalendarMapping` both ways plus `merge`, JVM-tested over the contract's tables: civil-time diff, parsed rule sets, the provider's cell formats
- [x] Accounts, the `Colors` palette and `Calendars` rows reconciled (name and colour kept in `CAL_SYNC1`, `CAL_SYNC2`); `CalendarSyncService`, `xml/syncadapter_calendar`, manifest, permissions; the hook a phone pass runs through before a removal (`CalendarRows.phonePass`)
- [x] The phone pass itself, filling `CalendarRows.phonePass` (with `CalendarRemote`)
- [x] `CalendarRemote`: enumerate (masters, created and cloned rows stamped, exception counts), fetch, guarded push in place, read-only revert, quiet path
- [x] `CalendarEngine` routing and the phone, server, phone passes; VTODO and VJOURNAL left out

## Phase 3
- [x] The switch in both setups and per calendar in the settings; one permission prompt
- [x] Detection of the address under another account type
- [x] Triggers: after writes, upload sync, on return, on a device zone change, background
- [x] Build, device half: `:app:assembleDebug`, `:app:testDebugUnitTest`
- [x] Build, iCalendar half: `cargo test`, clippy
- [ ] Device matrix: create, edit, delete in Google Calendar, Etar and Fossify; this occurrence only; this and following; an occurrence reverted; a reminder firing; an event in another zone; all-day; a read-only calendar edited; a Graph and a Google calendar
- [ ] Fold the delta into the spec, log, CHANGELOG

## Notes

- **Where the switch lives** (`PhoneCalendars`, SharedPreferences): the addresses whose calendars the phone shows, and among their calendars those kept off, by collection id, as `MergedFilter` keeps its hidden sets. The setup chooses per account before its first sync lists the calendars, so a calendar listed later joins the phone like its siblings; turning one on in settings in an account the phone does not show brings that calendar alone. Forgotten with the account.
- **Accounts** (`Accounts.reconcileCalendars`): named by the address, the address in the user data (`calendars`), never set on a book's account. Each kind's reconcile skips the other's accounts. A calendar account is syncable for `com.android.calendar` alone (`ContactsContract.AUTHORITY` set 0, since the contacts adapter is always syncable for our type), a book's account for contacts alone (calendar set 0); the calendar adapter is not always syncable, and answers an initialization sync for a book's account by setting it 0. Auto-sync on once per account, as the books'.
- **Rows** (`CalendarRows`): the values are pure (`insert`, `update`, `colors`); `reconcile` runs at startup (with the books), when a calendar's settings switch changes, on account removal, and after every calendar listing (`RemotePass.fetchCalendars`), since the setup chooses before the calendars are known and the server renames, recolours, adds and removes them. Without the calendar permission only the accounts follow. `IS_PRIMARY` is `DefaultCollection.of`. The colour is `Avatar.colorOf(collection colour, address)`, the agenda's.
- **`CALENDAR_TIME_ZONE`**: the rule is in `managed` (a zone `java.time` lists, else none), but the roster carries no `calendar-timezone` (no backend lists it), so the column stays empty until it does.
- **The hook** is `CalendarRows.phonePass(pimdir, collection)` now (the account is found from the collection's `Calendars` row), answering whether it brought an edit in; it runs `CalendarEngine.phonePass`. A row leaving (`leave`) or a row inserted makes the store forget the phone's bindings of that calendar (`PimdirStorage.forget`, drops as superseded), so a calendar shown again is projected whole; a new row gets its first pass at once.
- **The view** (`rust/src/calendar/phone.rs`, `Native.projectEvent` / `applyEvent`, `client.EventViews`): `{uid, master, overrides, listed}`, every component field optional, all of them filled by the projection; an edit sends what it changes, a new override its recurrence id and the fields taken alone, and `apply` patches each field that differs from what the object projects, so an untouched view comes back byte for byte. An empty object becomes a skeleton under the edit's `UID`. Times carry an `instant` when the phone moved them in a zone only the object defines. `Series`, `set_rule` and `Zones::local` were widened to the calendar module for it; `PRODID` became a constant shared with `create`.
- **The rows** (`CalendarMapping`): pure, under Robolectric only because the native library loads into one class loader per process. `rows(view, phone)`, `merge(view, rows, phone)` (field space, both sides through `rows`), `reverts` (a listed master's dates moved), `plan(existing, desired)` (in-place writes as plain values) and `fingerprint` (the dirty sentinel's digest).
- **The adapter** (`CalendarRemote`): one provider query for the quiet path, the listing stamping created rows and clones, linking Fossify's overrides and re-projecting clean rows projected for another zone or month (`SYNC_DATA4`); reads stamp behind assert queries; pushes go in one batch, guarded on `DIRTY`, the new rows' `SYNC_DATA3` set by back reference. The engine (`CalendarEngine.sync`) runs phone, server, phone under a process-wide lock per calendar, a failing phone pass never taking the server's down.
- **The conflict seam**: `CalendarEngine.phoneConflict(collection, conflict)` is where a phone binding both sides changed hands over to the calendar conflict triage (`calendar-conflict-form`), wired at the merge. Until then it logs and leaves the binding conflicted: the phone keeps its edit, the store the other side, nothing dropped (`CalendarMirrorTest.anEditBothSidesMadeStaysConflictedForTheTriage`).
- **A deletion on one source** used to drop that source's binding and leave the item live while the other still bound it, so a calendar-app deletion was handed back as a create and a server's never reached the phone (contacts included). `PimdirStorage.applyDrop` now leaves it a tombstone for the other source, and a source's own load skips a removal it already carried out.
- **Triggers**: the `staged` hook queues a calendar's pass (`PhoneQueue.calendarWritten`, one timer for books and calendars), `CalendarSyncService` on Android's syncs (counting what it brought in), the app's return (`SyncRunner.syncPhoneCalendars`, the agenda reloaded when it or the service brought something), the background run (also what moves a listed series' window), and `ZoneChange` on `TIMEZONE_CHANGED`.
- **Tests**: `phone.rs` (43), `CalendarMappingTest` (30), `CalendarMirrorTest` (16, end to end over `FakeCalendarProvider`, an in-memory provider with the dirty, soft-delete and batch semantics the mirror relies on), plus storage, queue, rows, steps and avatar cases.
- **Also from the device half's review**: `CalendarRows.reconcile` writes a `Calendars` row only when a column changed (`unchanged`); `Avatar.colorOf` reads CalDAV's `#RRGGBBAA` with its alpha last.
- **Detection** (`Accounts.calendarsElsewhere`): one `Calendars` query, `ACCOUNT_TYPE != org.pimalaya`, the names compared to the address case-insensitively in Java (`Accounts.names`). Said on the result page (both setups: the advanced one has no calendar page) and in settings, where the calendar's switch still shows it.
- **Settings**: a Calendars card per account covering calendars, a row per calendar with its filter state ("Shown in the list", "Hidden by the filter") and under it the box "Show in the phone's calendar", in the books' option style.
