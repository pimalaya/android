---
cairn: log
change: agenda-first-paint
date: 2026-10-30
---

# The agenda's first paint: timed, and no empty state before it

After the user found the calendar tab the one slow to first paint (2026-10-30).

- **calendar** (spec/calendar.md): "The agenda says it is empty only once read", added: `calendar_empty` starts gone (panel_calendar.xml) and `CalendarList` shows it only once a reload has landed. "The agenda's build is timed", added: `agenda read` and `agenda drawn` debug lines split a reload into its parts, as the calendar pass's pages already are.
- Measured on the user's device, a cold open in Week of 478 stored entries: 196 ms to one row, 103 ms reading every body, 44 ms expanding every entry, 21 ms rendering. The cause and its fix (lists from pimdir's summaries and SEARCH's occurrence index) are cairn/changes/lists-from-the-index, blocked on the draft-05 port (cairn/changes/io-pimdir-draft-05).

Capabilities moved: calendar.

Verified on 2026-10-30: `gradle :app:assembleDebug :app:testDebugUnitTest` green. Device check pending (the empty state no longer showing before a full calendar's rows).
