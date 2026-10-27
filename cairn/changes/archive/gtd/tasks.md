---
cairn: tasks
change: gtd
---

# Tasks

## Phase 0: design
- [x] Screens on the design canvas: Tasks tab lists, task page, capture dialog and widget, "Add to tasks" in the reader, the clarify flow, "Send and wait for a reply", the weekly review, the account settings line
- [x] Design reviewed and agreed (2026-10-27)
- [ ] Open questions of proposal.md settled (the build took provisional answers, see Notes)
- [x] delta.md: the requirements and scenarios, against spec/calendar.md, spec/mail.md and a new spec/tasks.md

## Phase 1: the model (Rust)
- [x] Read a VTODO's GTD fields: list, `CATEGORIES`, `RELATED-TO;RELTYPE=PARENT`, `PRIORITY`, `CONTACT`, `ATTACH` `mid:` (rust/src/calendar/task.rs, `Native.readTasks`)
- [x] Write them through the CST, everything else byte for byte (`EventEdit` gains `gtd`, `parent`, `contact`, `source`)
- [x] A dateless to-do: created without `DTSTART`, written without the series path
- [x] Tests: each field both ways, an untouched task round trips byte for byte, a task without `X-PIMALAYA-GTD` reads as a next action, another client's relation and attachment kept

## Phase 2: the Tasks tab (Java)
- [x] The lists and their filters (Next by context), the task page
- [x] Capture: sheet, tab button, widget (bar and Inbox count), launcher shortcut, share sheet, "Add to tasks" in the reader
- [x] Clarify flow, its answers one `Clarification` record
- [x] Waiting For from the composer (long press on Send), "reply received" from `In-Reply-To`
- [x] Weekly review
- [x] The Next widget: the open next actions, due first, a row opening its task (NextWidget, its rows from the list the tab last drew)
- [x] Agenda rows for dated to-dos opening the task page, back to the agenda; a conflicted one keeps the entry page
- [x] Account settings: a Tasks card (calendars taking tasks, the capture target picked by a tap, read-only ones events only, a line for accounts without tasks); onboarding: one line under a non-CalDAV calendar option (standard setup)
- [ ] "Reply received" from `References` too: the store keeps `In-Reply-To` alone, so it waits on the pimdir change (Later)

## Phase 3
- [x] Build: `:app:assembleDebug`, `:app:testDebugUnitTest` (751 tests), `cargo test` calendar (207), clippy
- [ ] Device run on a CalDAV account, a task edited in another client kept whole
- [x] Fold the delta into the spec, log, CHANGELOG

## Notes

- **Five tabs.** The app already had Files as a fourth tab, which the design canvas left out: Tasks sits between Calendar and Files.
- **Provisional answers to the open questions.** Waiting since is the task's `CREATED` (else `DTSTAMP`), not `DTSTART`: a `DTSTART` places a to-do on the agenda. Reference is `X-PIMALAYA-GTD:REFERENCE`, out of every list. Dropping a task is `STATUS:CANCELLED`, kept in its calendar. Due reminders are not done.
- **No phone-only collection.** The phone's local calendar takes no write (EventView), so a capture needs a CalDAV calendar; the sheet says so when none exists.
- **No server check of VTODO support.** A CalDAV calendar refusing VTODO (`supported-calendar-component-set`) is still offered; its push fails on the server's answer.
- **Raw SQL.** `TaskStore.replied` and `mailOf` read `mail_summary` with raw statements, as `EventStore` and `MailStore` already do; they belong in io-pimdir's canonical statements once the pimdir change lands.
- **Capture target.** The calendar captures go to is the one last picked, in the sheet or in account settings, kept in the device's "tasks" preferences; it answers the open question of the default collection for this draft.
- **Next widget.** It lists every open next action, not the tab's current context: a widget following a chip picked elsewhere would surprise. It has no done tick, as a tick would write from a broadcast with no screen to show a failure.
- **Onboarding line, standard setup only.** The advanced setup lists each server per domain and does not carry it.
- **Landed with three items open (2026-10-27).** The open questions keep their provisional answers above; "reply received" from `References` waits on a pimdir change keeping `References`; the device run is the user's. pimdir 1e59706 and io-pimdir d67d251 now carry a task's priority, categories, parent and source mail in the store, so the app may later read them there instead of off the body.
