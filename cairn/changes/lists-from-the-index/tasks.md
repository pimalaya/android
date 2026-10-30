---
cairn: tasks
change: lists-from-the-index
---

# Tasks

- [x] Time the calendar's first paint on the device (2026-10-30: 196 ms for 478 entries, 103 ms of bodies, 44 ms of expansion)
- [x] Decide the shape with the user (2026-10-30: calendar from SEARCH's occurrence index, Tasks and Contacts from an extended Annex A; wait on the pimdir and io-pimdir trees)
- [x] pimdir and io-pimdir in-flight work committed (the user, 2026-10-30)
- [ ] Blocked: the draft-05 port (cairn/changes/io-pimdir-draft-05), then the two-SQLite question (proposal)
- [ ] pimdir (storage-draft-06, search-draft-06): task_summary `created` and the `contact` address role; `occurrences_between`; cards' `has:phone|address|birthday|photo` and `category:` in the index
- [ ] io-pimdir: the draft-06 writers, extraction flags and `occurrences_between`; rev moved here once the port allows
- [ ] App: `search` in the cdylib, the index refreshed after sync and on start, the agenda through `occurrences_between`
- [ ] App: Tasks and Contacts paging the canonical statements alone; `PimdirContacts.list`'s hand-written SQL gone
- [ ] Measure again (the `agenda read` and `agenda drawn` logs), then drop the timing log
- [ ] Delta, fold, log, CHANGELOG
