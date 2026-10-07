---
cairn: log
change: week-navigation
landed: 2026-10-07
---

# Week navigation and row touch-ups

Capabilities moved: calendar (modified: the agenda offers the week), mail (modified: a row's trailing marks), offline-store (modified: the three lists share one row; added: an empty list says so below its header).

**Week.** `CalendarList.weekOffset` counts weeks from this one. The header row carries the month and year, a tonal week-number pill that resets the offset to 0, and two chevrons. The expansion window runs from the earlier of this week's and the shown week's first day to the later of the 120-day horizon and the shown week's end. With no day picked, this week shows everything from today on and any other week shows itself. The number and the month come from the week's Thursday, with four minimum days in the first week, the ISO 8601 assignment; the first weekday stays the locale's.

**Mail row.** The replied mark moved to lead the sender's line. On the subject's line the paperclip is now at the edge and the star inside it. The dot on the line above takes margins (10dp, 4dp) that centre its 8dp on the paperclip's 16dp column.

**Agenda row.** The kind-of-entry glyph gave way to its name, which leads the second line before the length.

**Empty states.** Each list's empty view fills the panel with centred content. `ListHeader.empty` keeps its top padding at the header's bottom, so it centres in the space below the header.
