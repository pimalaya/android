---
cairn: change
id: gtd-hardening
status: landed
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

- **The bump.** io-pimdir to its gtd-hardening change, pinned by git rev in rust/Cargo.toml's `[patch.crates-io]` (a path entry to ../io-pimdir while that change was uncommitted), fetched from the local checkout until the rev is pushed. API drift adapted with the smallest correct changes, the Java store included where the engine's wire grew (the load's `held`).
- **Summaries (M2).** Every calendar body the app lists or stages carries the summary and sort key io-pimdir derives from it (`pimdirDerive`), the task row's four new columns crossing the wire; a store holding calendar items with no summary row derives them once, off the main thread, before the Tasks tab reads. The Tasks tab finds its to-dos through the canonical `list_tasks_page_asc` (an item with a task row is a task) and reads only their bodies: an agenda of thousands of events costs no body read.
- **Writers (M1).** rust/src/calendar/edit.rs writes a to-do as GUIDE §17's table says; a capture states `CREATED`; an edit whose result the task already holds writes nothing and stages nothing; a category edit naming a reserved value is refused. A test in rust/src/calendar runs vectors/gtd/writers.json from ../../pimdir (or `PIMALAYA_PIMDIR_VECTORS`), every case. The Tasks tab folds the category filter and orders Next by due, priority, then oldest captured, the other lists oldest captured first.
- **Moves (H4).** Filing into another calendar is one transaction staging the edited add in the target, the source's tombstone and the pair in `supersessions` (canonical `supersede_item`); a load answers the held tombstones (`list_superseded_pending`), so the engine pushes no delete while the add is a pending create. The new item keeps the source's link id and `UID`.
- **Capture targets (M6).** A CalDAV listing states each calendar's `supported-calendar-component-set`; the store declares `x-vtodo` `none` for one naming components but not VTODO, and `full` otherwise, through the canonical `set_capability`; a calendar is offered for tasks when writable, over CalDAV, and no source syncing it states `x-vtodo` `none` there (canonical `load_capabilities`, as io-pimdir's `accepts_vtodo` reads it); a create or move into another one is refused.
- **Refused creates (follow-up, 2026-10-29).** A 403 or 409 answered to one create refuses that item as 412 and 422 already do, the create staying staged and the pass going on, as io-pimdir's engine and neverest treat a refused add (a superseded source stays on its server); a 401 and a throttle 403 still fail the pass. Fastmail answers a move between two of its calendars 403 "WebDAV collection already holds a resource with the same UID", which failed that calendar's pass on every sync; io-webdav reads that refusal as a duplicate `UID`, whose status the bridge dropped, so it also crosses with its status now.
- **Replies (follow-up).** TaskStore.replied runs io-pimdir's `list_replied`, the statement `PimdirReader::replied` runs, through the bridge's statement export: the person's own mail under `sent` or `drafts` answers nothing, and one statement serves every item.
- **Targets before the first sync (follow-up).** The listing records the server source as syncing each calendar it lists, so the `x-vtodo` it declares counts from the listing on, not from the first round.
- **Filing by a move (follow-up, 2026-10-29).** Fastmail keeps one `UID` per account, so §5 left a filed task in its source for good. After io-webdav b2b9a00 (`move_item`, WebDAV `MOVE`) and io-pimdir 8907303 (an add superseding a member of its identity names it, `supersedes`, pimdir SYNC §3, §4), both pinned by rev, the store's load names the member each pending create may be moved from (canonical `list_superseded_bindings`), and CalendarEngine delivers that create by moving the member into the target calendar, gated on its ETag, then writing the filed body over it; a move the server cannot do (405, 501, Google's 404) is an upload as before, one refused for the identity or the ETag refuses the create. The source's held delete that follows finds the member gone, which counts as done, a strict server's 412 for an object gone included. The bump also addresses a CalDAV or CardDAV collection outside the home set by its path, as io-webdav now names it.
- **Tests.** Rust unit tests, the writer vectors, Gradle unit tests (Robolectric, real bridge and store) for summaries, moves, holds, capabilities, ordering and folding; an E2eTaskTest on `fastmail-caldav` (capture, clarify, file elsewhere, send and wait) in scratch calendars.

## Out

- A device run of the Tasks tab (the user's).
- Declaring `x-vtodo` for Graph, Google Calendar API or JMAP calendars: none of them takes tasks over this app already (`takesTasks`).
- Folding the delta, the log, archiving, a version bump: landing waits for review.
