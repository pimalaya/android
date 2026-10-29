---
cairn: change
id: gtd-hardening
status: active
created: 2026-10-29
---

# The Tasks tab writes as unfog does, keeps task summaries, files safely and knows where tasks go

## Why

Track F of pimalaya/unfog cairn/changes/gtd-hardening (H1, H4, M1, M2, M6), after pimdir cairn/changes/gtd-hardening (GUIDE §17 writing rules, STORAGE §15.3 `supersedes`, §15.6 `x-vtodo`, vectors/gtd/writers.json) and io-pimdir cairn/changes/gtd-hardening (the engine's hold, the capability, the task columns).

- **H1, M2: the phone keeps no task summaries.** The bridge pins io-pimdir 189db40, which predates `task_summary`'s `priority`, `categories`, `parent` and `mid`. CalendarEngine.java:213-216 lists every calendar object "with no key and no summary", TaskWrites stages with a null summary, so `task_summary` stays empty on the phone, and TaskStore.java:73 reads and scans every calendar object's body on every load to find the to-dos. spec/offline-store.md already asks the store to derive a summary through io-pimdir wherever the body is on hand.
- **M1: two writers of one convention disagree.** The proposal's table and pimdir GUIDE §17 decide: a task in its state already untouched (Android already), a plain category edit keeping the state and only the person's values replaced in place (Android collapses every `CATEGORIES` line into one), a category filter folded (Android compares exactly, TasksList.java:373), Next by due then priority then oldest (Android stops at priority, TasksList.java:466), `CONTACT;ALTREP="mailto:..."` (Android already), `CREATED` at capture (never written), `DTSTAMP` restated where it stands and `LAST-MODIFIED` only where the task carries one (Android moves both to the end and adds `LAST-MODIFIED`). pimdir's writer vectors check it byte for byte, and unfog already passes them.
- **H4: a move stages its remove before the add is performed.** TaskWrites.java:104-108 stages an add in the target and a tombstone of the source at once, so a refused create (Fastmail answers 403 to a second resource of one `UID` in a calendar home, the unfog suite found on 2026-10-29) still lets the delete through: the capture is lost on the server.
- **M6: the phone offers calendars that refuse VTODO.** `TaskStore.takesTasks` takes any writable http(s) calendar, so a Google CalDAV calendar (VTODO PUT answers 403) is offered as a capture target.

## What

- **The bump.** io-pimdir to the working tree of ../io-pimdir (its gtd-hardening change, uncommitted on 2026-10-29), through an uncommitted path entry in rust/Cargo.toml's `[patch.crates-io]` for local builds only, until the rev is pushed and pinned. API drift adapted with the smallest correct changes, the Java store included where the engine's wire grew (the load's `held`).
- **Summaries (M2).** Every calendar body the app lists or stages carries the summary and sort key io-pimdir derives from it (`pimdirDerive`), the task row's four new columns crossing the wire; a store holding calendar items with no summary row derives them once, off the main thread, before the Tasks tab reads. The Tasks tab finds its to-dos through the canonical `list_tasks_page_asc` (an item with a task row is a task) and reads only their bodies: an agenda of thousands of events costs no body read.
- **Writers (M1).** rust/src/calendar/edit.rs writes a to-do as GUIDE §17's table says; a capture states `CREATED`; an edit whose result the task already holds writes nothing and stages nothing; a category edit naming a reserved value is refused. A test in rust/src/calendar runs vectors/gtd/writers.json from ../../pimdir (or `PIMALAYA_PIMDIR_VECTORS`), every case. The Tasks tab folds the category filter and orders Next by due, priority, then oldest captured, the other lists oldest captured first.
- **Moves (H4).** Filing into another calendar is one transaction staging the edited add in the target, the source's tombstone and the pair in `supersessions` (canonical `supersede_item`); a load answers the held tombstones (`list_superseded_pending`), so the engine pushes no delete while the add is a pending create. The new item keeps the source's link id and `UID`.
- **Capture targets (M6).** A CalDAV listing states each calendar's `supported-calendar-component-set`; the store declares `x-vtodo` `none` for one naming components but not VTODO, and `full` otherwise, through the canonical `set_capability`; a calendar is offered for tasks when writable, over CalDAV, and no source syncing it states `x-vtodo` `none` there (canonical `load_capabilities`, as io-pimdir's `accepts_vtodo` reads it); a create or move into another one is refused.
- **Tests.** Rust unit tests, the writer vectors, Gradle unit tests (Robolectric, real bridge and store) for summaries, moves, holds, capabilities, ordering and folding; an E2eTaskTest on `fastmail-caldav` (capture, clarify, file elsewhere, send and wait) in scratch calendars.

## Out

- A device run of the Tasks tab (the user's).
- `PimdirReader::replied`'s rule on the phone (sent and drafts mail answering nothing): TaskStore.replied keeps its own query.
- Declaring `x-vtodo` for Graph, Google Calendar API or JMAP calendars: none of them takes tasks over this app already (`takesTasks`).
- Folding the delta, the log, archiving, a version bump: landing waits for review.
