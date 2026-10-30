---
cairn: delta
change: calendar-list-mode
---

Folds into `spec/calendar.md`.

## ADDED Requirements

### Requirement: The agenda lists or shows the week
The calendar's bar SHALL carry a toggle of two modes, List and Week, the mode on drawn on the nav indicator's tone and kept across restarts, List by default. Week SHALL be the week card's agenda. List SHALL carry no week card and SHALL list, by day, every entry the search and the filter keep within its timeframe, each day headed Today, Tomorrow, Yesterday, else its weekday and date, with the year when it is not this year's, and each month other than today's opened by its name and year in full.

#### Scenario: Switching to Week
- GIVEN the List mode
- WHEN Week is pressed in the bar
- THEN the week card shows this week, and the mode is still Week after a restart

### Requirement: The list splits at now
The List mode's filter panel SHALL offer Timeframe under Calendars, one choice of Upcoming (the default) or Past, a pill while Past is on; Week SHALL neither offer nor follow it. Upcoming SHALL list the entries that have not ended, earliest first; Past the entries that have ended, latest first. The list SHALL expand three months from today in its direction, When's From and To narrowing it and widening it to a day they name beyond.

#### Scenario: A meeting running now
- GIVEN a meeting from 10:00 to 11:00 and a lunch from 08:00 to 09:00 today, it being 10:30
- WHEN the list shows Upcoming
- THEN the meeting heads Today and the lunch is not listed
- AND with Past on, the lunch heads Today, above yesterday's entries

#### Scenario: A To date in six months
- GIVEN an entry in five months and Upcoming on
- WHEN When's To is set six months ahead
- THEN the entry is listed

## MODIFIED Requirements

### Requirement: The agenda builds off the main thread
The agenda SHALL read and expand its entries on a thread of its own and draw the result on the main thread, the last reload asked winning. An entry's expansion SHALL be kept while the window shown (the week, or the list's) and its object stay the same.

#### Scenario: Coming back to the app
- GIVEN 10k stored entries
- WHEN the app returns to the foreground
- THEN the screen stays responsive while the agenda rebuilds, and only entries a sync changed are expanded again

## REMOVED Requirements
