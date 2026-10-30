---
cairn: log
change: event-page-sections
date: 2026-10-30
---

# The entry page in four sections; one form per alarm; clearable dates

- **calendar** (spec/calendar.md): the entry page shows Calendar, When, Details and Alarms, always, the bar's add button gone; When shows its rows always with no add button, Details the title always and the rest once set or added (Progress and Where folded into it); a date picker clears every date but an event's start; an alarm is added and edited in one form (type, time, message, an email's subject and recipients); the calendar dropdown takes a row's padding.

Capabilities moved: calendar.

Verified on 2026-10-30: `gradle :app:assembleDebug :app:testDebugUnitTest` green; cairn/verify.sh conformant. Device check pending.
