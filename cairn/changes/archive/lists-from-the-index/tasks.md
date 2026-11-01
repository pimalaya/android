---
cairn: tasks
change: lists-from-the-index
---

# Tasks

- [x] Time the calendar's first paint on the device (2026-10-30: 196 ms for 478 entries, 103 ms of bodies, 44 ms of expansion)
- [x] Decide the shape with the user (2026-10-30: calendar from SEARCH's occurrence index, Tasks and Contacts from an extended Annex A; wait on the pimdir and io-pimdir trees)
- [x] pimdir and io-pimdir in-flight work committed (the user, 2026-10-30)
- [x] Blocked: the draft-05 port (cairn/changes/archive/io-pimdir-draft-05), then the two-SQLites question (proposal): both settled by cairn/changes/archive/platform-sqlite, steps 2 to 4
- [x] pimdir (storage-draft-06, search-draft-06, ffe31a4): task_summary `created` and the `contact` address role; `occurrences_between`; cards' `has:phone|address|birthday|photo` and `category:` in the index
- [x] io-pimdir: the draft-06 writers, extraction flags and `PimdirIndex::occurrences_between` (a8bfbf0, needs 0.9.0)
- [x] Rev moved here once the draft-05 port allows (io-pimdir fd0c060, platform-sqlite step 4)
- [x] App: `search` in the cdylib, the index refreshed after sync and on start (platform-sqlite step 4), the agenda through the index's occurrence table (2026-11-01: `AGENDA_OCCURRENCES`, `lists::agenda`, `Native.pimdirIndexAgenda`), floating times read in the device's zone, horizon and cut said on the header
- [x] App: Contacts listing from `contact_summary` and `item_address` (`LIST_CARDS`), Has and categories from the index (`CARD_FACTS`, `CARD_CATEGORIES`), search beside a `kind:contact` query; `PimdirContacts.list`'s hand-written SQL already gone at platform-sqlite step 2b
- [x] App: Tasks listing from `task_summary`, `task_category` and the `contact` address (`LIST_TASK_ROWS`, `LIST_TASK_CATEGORIES`, `Native.tasksOf`), the body read by the page alone
- [x] Measure again, then drop the timing log: on the host (`AgendaMeasure`, 478 entries) a week 18 ms expanded against 3 ms from the index, the list's 92 days 25 ms against 15 ms; the `agenda read` and `agenda drawn` logs dropped (cairn/log/2026-11-01-lists-from-the-index.md)
- [x] Device check: the three lists on the phone, the contact row's second line (no phone) settled with the user (Android 15 and the API 26 emulator on 2026-11-01, the second line settled with the user: the email, else the organization, no phone (cairn/log/2026-11-01-platform-sqlite-step-5.md); what the check did not cover is Left in cairn/log/2026-11-01-lists-from-the-index-landed.md)
- [x] Delta (delta.md) and log; CHANGELOG
- [x] Fold the delta into spec/calendar.md, spec/tasks.md and spec/offline-store.md, archive, at landing with platform-sqlite (folded on 2026-11-01, archived with platform-sqlite)
- [x] Landed on 2026-11-01: delta folded, folder archived; every item a task above leaves open is recorded in the landing entry's Left section (cairn/log/2026-11-01-lists-from-the-index-landed.md)
