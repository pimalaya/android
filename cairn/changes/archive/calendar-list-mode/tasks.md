---
cairn: tasks
change: calendar-list-mode
---

# Tasks

- [x] Design boards on the canvas (Views 1 and 2)
- [x] Review the intent (the user, 2026-10-30: two modes, list and week; past entries a criterion of the list alone, split at now)
- [x] Bar toggle: `calendar_modes` in activity_main.xml, shown on the calendar's chrome, icons `ic_view_agenda` and `ic_calendar_view_week`, dimens, strings en and fr
- [x] `CalendarList`: the mode kept in prefs, List hiding the week card (`ListHeader.noWeek`), its window and order, Timeframe drawn in List alone
- [x] Tests: the list window (`CalendarList.listDays`) for Upcoming, Past and When bounds widening it
- [x] `:app:assembleDebug :app:testDebugUnitTest` green, debug APK installed
- [x] Device check, round 1 (the user, 2026-10-30): toggle icons kept; asked for Timeframe under Calendars, month headings in the list, a date icon on From, To and the tasks' Due day
- [x] Round 1 fixes built, tested and installed
- [x] Round 2 (the user, 2026-10-30): the toggle buttons carry their label beside the icon (TextViews with a start drawable, `ripple_pill`); the month headings, a misread of that ask, stay as the board draws them
- [x] Round 3 (the user, 2026-10-30): the labels read badly, the buttons are icons alone again
- [x] Device check, round 4 (the user asked to commit, 2026-10-30)
- [x] Fold the delta into spec/calendar.md, log, CHANGELOG.md, archive
