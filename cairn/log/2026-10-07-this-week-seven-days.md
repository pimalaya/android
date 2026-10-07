---
cairn: log
change: this-week-seven-days
landed: 2026-10-07
---

# This week shows its seven days

Capabilities moved: calendar (modified: the agenda offers the week).

With no day picked, the current week no longer lists everything from today on: like every other week, it lists the seven days from the locale's first weekday, its past days included. The filter is `CalendarList.kept`, a pure function of the day, the picked day and the shown week's first day, covered by `CalendarListTest`. The expansion window, which ran from this week's first day to the later of a 120-day horizon and the shown week's end, now covers the shown week alone, since a picked day always falls in it.
