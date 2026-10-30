---
cairn: log
change: event-calendar-dropdown
date: 2026-10-30
---

# A new entry opens at once, its calendar a dropdown on top; full-width dialog fields

- **calendar** (spec/calendar.md): the agenda's add button opens the entry page at once in the account's default calendar, else the first writable one; while creating, a dropdown on top of the page names each writable calendar with its account when there are several, switching keeping what the form holds; the bottom Calendar section is gone. The entry page's edit dialogs lay their field out at the dialog's width.

Capabilities moved: calendar.

Verified on 2026-10-30: `gradle :app:assembleDebug :app:testDebugUnitTest` green; cairn/verify.sh conformant. Device check pending.
