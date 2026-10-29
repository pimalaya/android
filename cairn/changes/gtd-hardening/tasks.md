---
cairn: tasks
change: gtd-hardening
---

# Tasks

Track F of pimalaya/unfog cairn/changes/gtd-hardening; waits on pimdir and io-pimdir cairn/changes/gtd-hardening.

## 1. Bump and summaries (H1, M2)

- [x] rust/Cargo.toml: io-pimdir by an uncommitted path patch to ../../io-pimdir (local builds only), API drift adapted
- [x] The task row's `priority`, `categories`, `parent`, `mid` on the summary wire
- [x] Listing (CalendarEngine, CalendarRemote) and staging (CalendarStager) derive the summary and sort key
- [x] A store's calendar items with no summary are derived once before the Tasks tab reads
- [x] TaskStore finds its to-dos through `list_tasks_page_asc`, reading only their bodies

## 2. Writers (M1)

- [x] edit.rs: GUIDE §17's table (state, categories, contact, stamps, unchanged, refusal); `CREATED` at capture
- [x] rust/src/calendar vector runner over pimdir's vectors/gtd/writers.json, every case passing; shown failing against the old writers first
- [x] TaskWrites stages nothing for an unchanged edit
- [x] TasksList: folded category filter; Next by due, priority, oldest; others oldest first

## 3. Moves and capture targets (H4, M6)

- [x] moveTo: add, tombstone and `supersede_item` in one transaction; the load's `held`
- [x] CalDAV listing states components; the store declares `x-vtodo`; `takesTasks` reads `load_capabilities`; writes into a refusing calendar refused

## 4. Tests and build

- [x] Gradle unit tests: summaries, unchanged edit, move and hold, capability, order and filter
- [x] E2eTaskTest on `fastmail-caldav`: capture, clarify, file elsewhere, send and wait
- [x] `cargo fmt`, `cargo clippy --all-targets`, `cargo test`; `:app:assembleDebug :app:testDebugUnitTest`
- [x] CHANGELOG [Unreleased]

## 5. Landing (after review)

- [ ] Pin io-pimdir to the pushed rev, drop the path patch
- [ ] Fold the delta, log, archive

## Notes

- The patch is `io-pimdir = { path = "../../io-pimdir" }` in rust/Cargo.toml's `[patch.crates-io]`, the 189db40 git line kept in a comment; Cargo.lock drops the git `source` of io-pimdir. Drift met: the task row's four columns on the summary wire (rust/src/offline/summary.rs), `PimdirLoaded::held` (rust/src/offline.rs, filled by the Java store's load), `SetRoundCursor::cursor` an `Option` (absent on the wire keeps the cursor), `PimdirListing::Head` written as a `head` kind no connector reads (the bridge never enables heads).
- The Tasks tab finds its to-dos by their `task_summary` row and reads only their bodies; a task's fields still come from its body, `task_summary` holding no `CONTACT`, `CREATED` or `DESCRIPTION`. The backfill of items with no summary runs at the tab's load, off the main thread, not when the store opens.
- A calendar item's link id stays its resource name (this store's identity), not the `UID` io-pimdir derives; a filed task keeps its source's link id, the name the target's server is asked to file it by.
- GUIDE §17's writing applies to every to-do edit, from the agenda's entry page too (EventEditTest's `todo` case now expects each line where it stood and no `LAST-MODIFIED` added); an event's edit writes as before.
- A move is one transaction (`PimdirStorage.atomically`): the add, `supersede_item`, then the tombstone, so a source never pushed is withdrawn with its pair. The hold needs the target synced once (a source syncing it), as `list_superseded_pending` reads it.
- `x-vtodo` is declared per collection on the `server` source with `set_capability`, never `delete_capabilities`, that source being every account's; before a calendar's first sync no source syncs it, so nothing refuses there, as io-pimdir's `accepts_vtodo` reads it.
- A 403 on a create still fails the calendar's pass (only 422 and 412 read as a rejection), the create staying pending and retried: unchanged here.
- E2eTaskTest ran live on 2026-10-29 against `fastmail-caldav` (`-Pe2e.prefix=unfog-e2e-`, scratch calendars only, the account's own listed and never synced): passed. A Fastmail calendar made by MKCALENDAR with no component set states one without VTODO, so the app declared it `x-vtodo` `none` and refused the capture (first run); the test's calendars now state VEVENT and VTODO. Filing into the second calendar: Fastmail answered both passes 403 "WebDAV collection already holds a resource with the same UID", and the task stayed in its source calendar on the server, the superseded delete held. Nothing of the run is left on the server. The harness gained `-Pe2e.prefix` (default `pimalaya-e2e-`), a scratch-only calendar scope, a component set on a scratch calendar and a read of a scratch calendar's objects.
