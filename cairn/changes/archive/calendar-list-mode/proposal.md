---
cairn: change
id: calendar-list-mode
status: landed
created: 2026-10-30
---

# The agenda's two modes: a list from now, and the week

Design: the canvas row "Calendar views: list and week (proposed)", boards Views 1 and 2 (https://claude.ai/artifact/UJsQXRtqaiQZ6ufXpqAZLW).

## Why

User feedback (2026-10-30): the calendar tab should read like Mail and Tasks, a list of entries grouped by day, rather than only one week at a time. Day, week and month grids were drawn and dropped: two modes are enough, the list and the week card the agenda has today.

## What

- **Two modes, a toggle in the bar.** The calendar's bar carries one pill of two icon buttons, List (Material Symbols `view_agenda`) and Week (`calendar_view_week`), the mode on taking the nav indicator's tone. The mode is kept across restarts; List is the default.
- **Week is the agenda as it is.** The week card, its arrows, its number and the pressed day, unchanged (spec/calendar.md, "The agenda offers the week").
- **List has no week card.** It lists every entry of the calendars kept by the filter, by day, under the same header (title, count, search, filter panel and pills).
- **A Timeframe, in List alone.** The filter panel of the List mode gains Timeframe, one choice: Upcoming (the default) or Past, a pill while Past is on. The two split at now: Upcoming is what has not ended, earliest first, today's running entries included; Past is what has ended, the latest first, its days from yesterday backwards and today's ended entries on top. Week ignores it and does not offer it, keeping today's rule of dimming what is over under the rest.
- **A window, not the whole calendar.** The list expands three months from today in its direction (forward for Upcoming, back for Past). When's From and To narrow it, and a To past the three months (a From before them, for Past) widens it to that day, so any span stays reachable. A Load more footer is left for later.
- **Day headings.** Today, Tomorrow, Yesterday, else the weekday and date, with the year when it is not this year's.
- **Month headings.** In List, each month other than today's opens with its name and year in full ("November 2026", the week card's style), above its first day (`CardSections` grouping sections, a third view type in `SectionsAdapter`).
- **Round 2026-10-30 (the user, first device look).** Timeframe sits under Calendars; When's From and To, and the Tasks panel's Due day, lead with a `calendar_today` icon in the choices' glyph column (`FilterPanel.action` with a glyph).

## Out of scope

- Day, week and month time grids.
- Load more past the window.
- Keeping the pressed day or the shown week when switching modes: switching to Week opens on this week.

## Open questions

- Whether three months is the right window before Load more exists.
