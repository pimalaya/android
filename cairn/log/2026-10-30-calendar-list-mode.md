---
cairn: log
change: calendar-list-mode
date: 2026-10-30
---

# The agenda lists from now, or shows the week

Designed on the canvas (row "Calendar views: list and week", boards Views 1 and 2) after day, week and month grids were drawn and dropped, then tuned over three device rounds the same day.

- **calendar** (spec/calendar.md): the bar carries a List and Week toggle, icons alone (`view_agenda`, `calendar_view_week`), the mode kept across restarts, List by default. Week is the week card's agenda, unchanged. List has no week card and lists by day, Yesterday added to the headings, the year when not this year's, each month other than today's opened by its name and year in full (`CardSections` groups, a third view type in `SectionsAdapter`). Its panel offers Timeframe under Calendars, Upcoming or Past, split at now, earliest or latest first, over three months from today that When's From and To narrow or widen (`CalendarList.listDays`). The agenda keeps an expansion while its window, week or list, stays.
- **ui**: `FilterPanel.action` takes a leading glyph; When's From and To and the Tasks panel's Due day lead with `calendar_today`.

Capabilities moved: calendar.

Verified on 2026-10-30: `gradle :app:assembleDebug :app:testDebugUnitTest --rerun-tasks` green, CalendarListTest's three `listDays` cases; installed on the user's device, checked over three rounds. Left for later: Load more past the three months, and an entry begun before yesterday and still running, which the list's window by start does not read.
