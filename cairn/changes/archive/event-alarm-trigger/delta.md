---
cairn: delta
change: event-alarm-trigger
---

Folds into spec/calendar.md.

## ADDED Requirements

## MODIFIED Requirements

### Requirement: An entry's alarms are the phone's to ring
The page of a CalDAV event SHALL show every alarm of its master component, whatever its action, each saying its type (a display alarm as a notification, an audio one as a sound, an email one as an email, another action as written), when it rings (off the start, off the end, or at a moment), its message, an email's recipients and how it repeats. A row SHALL open one form holding its type, when it rings (an amount of minutes, hours, days or weeks before or after the start or the end, or a date and time, written as a UTC date-time as RFC 5545 3.8.6.3 requires), how many more times it rings and how far apart, its message, and an email's subject and recipients, with a Remove button; a new alarm SHALL open the same form, its message the entry's title, an email one addressed to the account under that title. The app SHALL NOT ring an alarm itself: the phone's calendar provider rings the display and audio alarms the mirror projects, and another client acts on the rest. A save SHALL write the alarms one by one: an alarm kept SHALL stay byte for byte, a changed one SHALL have only what moved rewritten, a dropped one SHALL go, and a new one SHALL carry what RFC 5545 3.6.6 asks of its action (a description; an email one a subject and its recipients too). A repeat with no interval SHALL be none, `REPEAT` and `DURATION` going together. Alarms SHALL be the whole entry's: a save changing them alone SHALL ask no scope. A Microsoft or Google entry, a to-do, a journal entry and an entry in conflict SHALL show no alarm section.

#### Scenario: An alarm set on a new entry
- GIVEN a new event in a CalDAV calendar
- WHEN a notification 15 minutes before is added and the page saved
- THEN the entry carries a `VALARM` with `ACTION:DISPLAY` and `TRIGGER:-PT15M`

#### Scenario: Another alarm kept as it was
- GIVEN an event carrying a display alarm 10 minutes before and an email alarm an hour before
- WHEN the display alarm is removed and the page saved
- THEN the email alarm is still there, byte for byte

#### Scenario: An email alarm
- GIVEN an event with no alarm
- WHEN an email alarm a day before is added
- THEN the entry carries a `VALARM` with `ACTION:EMAIL`, `TRIGGER:-P1D`, the title as `DESCRIPTION` and `SUMMARY`, and the account's address as `ATTENDEE`

## REMOVED Requirements
