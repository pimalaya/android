---
cairn: log
change: lists-from-the-index
date: 2026-11-01
---

# Lists from the summaries and the index: landed

Built as platform-sqlite step 5 (cairn/log/2026-11-01-lists-from-the-index.md, cairn/log/2026-11-01-platform-sqlite-step-5.md) and checked on an Android 15 phone and an API 26 emulator, landed with cairn/changes/archive/platform-sqlite. The change is archived at cairn/changes/archive/lists-from-the-index.

Capabilities moved:

- **calendar** (spec/calendar.md): ADDED "The agenda reads the index's occurrences"; MODIFIED "The agenda builds off the main thread" (its window read off the index, the kept expansions only with no index); REMOVED "The agenda's build is timed". The introduction says the index expands an entry, not the render.
- **tasks** (spec/tasks.md): MODIFIED "The Tasks tab reads its to-dos from the summaries" (no body read to list, the page reading it).
- **offline-store** (spec/offline-store.md): ADDED "The contacts list reads the summaries and the index"; MODIFIED "The three lists share one row".

Decided with the user on 2026-11-01 and stated in the folded "The three lists share one row", with a scenario: a contact row's second line is the first email, else the organization, never a phone, which pimdir's summaries do not hold (STORAGE Annex A.2, A.6); the delta was amended at landing to say so.

## Left

- The contact row shows no phone: a summary column for it would change pimdir's canonical schema and reinstall every draft-08 store, which the user declined; pimdir's to add if ever.
- Annex A.3 holds the master's `SUMMARY` and `LOCATION`: an override's own title shows on its page, not on its agenda row.
- A summary's times carry no offset: a task's `DUE` in a `TZID` only its own `VTIMEZONE` defines reads in the device's zone on the list.
- `index_meta.floating_zone` is one zone: between a change of the device's zone and the refresh after it, an all-day entry is placed on its day in the old zone.
- An event spanning into the window from before it is drawn on the day it starts alone, as before, so not on the window's days.
- The contacts' grouping is recomputed at each reload (from summary columns, no body); keeping it between reloads stays open.
- Not covered by the device check: the Tasks tab on the phone, an upcoming entry on the agenda (the test account's four events were past), a sync writing while the agenda reads, a change of zone.
