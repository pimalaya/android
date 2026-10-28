---
cairn: tasks
change: gtd-by-categories
---

# Tasks

## Phase 0: design
- [ ] Canvas updated: the tab without Projects and Reference, the category filter, the capture dropdown, clarify's steps as edits (the user's design artifact)
- [x] delta.md against spec/tasks.md, spec/mail.md and spec/calendar.md (calendar unchanged)

## Phase 1: the model (Rust)
- [x] rust/src/calendar/task.rs: the state from `CATEGORIES` (folded as pimdir folds `fn`), `X-PIMALAYA-GTD` dropped both ways; `Task` carries `list`, `states` and `categories` (the person's own)
- [x] Writes add or remove the three values only, every other category kept in order, the rest of the body byte for byte (rust/src/calendar/edit.rs `set_state`); a categories edit on a to-do keeps its state
- [x] Tests: each state both ways, a cased `Inbox` read, a person's category kept beside `waiting`, a task with `X-PIMALAYA-GTD` read as a next action and written back untouched; each fails against the old code (11 run against the old task.rs and edit.rs, all failed)

## Phase 2: the app (Java)
- [x] Tasks tab: Inbox, Next, Waiting, Someday, the category filter; Projects and Reference removed
- [x] Capture: `CATEGORIES:inbox`, the collection dropdown (last used, hidden with one, explained with none)
- [x] Clarify: each step an edit; a collection change is create then remove once accepted
- [x] Send and wait: `waiting`, `CONTACT`, `mid:`
- [x] Weekly review and Next widget follow
- [x] Strings (en, fr): labels for the three values

## Phase 3: landing
- [x] Build bar: `nix develop --command gradle -p android :app:assembleDebug :app:testDebugUnitTest --console=plain` (754 tests), `cargo test` calendar (215), clippy, `cargo fmt`
- [ ] Device run (the user's)
- [x] Fold the delta, log, CHANGELOG [Unreleased] line rewritten (no `X-PIMALAYA-GTD`), status landed, archive

## Notes

- **The calendar filter still applies.** The lists are global across accounts and calendars, but a calendar hidden on the filter page keeps its tasks hidden too, as before: the filter page is the app's display choice for every domain, and nothing in the proposal asked to bypass it.
- **No default calendar.** The account Tasks card no longer picks where captures go: it says which calendars take tasks. The capture sheet's dropdown keeps the last one used, which Send and wait for a reply follows too.
- **Delete is a removal.** Not actionable and not Someday deletes the item (a tombstone, restorable from Deleted items until a purge), where the former flow cancelled it; Drop on the task page still writes `STATUS:CANCELLED`.
- **Two states at once.** A task another client marked with two reserved values shows in both lists, each view being one query (pimdir GUIDE §17); `list` is the first in the order `inbox`, `waiting`, `someday`.
- **Subtasks.** A task whose parent is in the same list is drawn right under it; the task page and the clarify flow pick a parent among the open tasks ("Part of").
