---
cairn: delta
change: event-alarms
---

Folds into spec/calendar.md.

## ADDED Requirements

### Requirement: An entry's alarms are the phone's to ring
The page of a CalDAV event SHALL show its alarms as the phone mirror reads them (a display or audio alarm off the start or the end, or at a time on an entry that does not recur), each labelled against the start, and SHALL let one be changed among presets, removed, or added; the app SHALL NOT ring an alarm itself, the phone's calendar provider ringing what the mirror projects. A save SHALL write the alarms through the mirror's patch: an alarm kept SHALL stay byte for byte, a new one SHALL be a display alarm, and an alarm the mirror does not read SHALL never be touched. Alarms SHALL be the whole entry's: a save changing them alone SHALL ask no scope. A Microsoft or Google entry, a to-do, a journal entry and an entry in conflict SHALL show no alarm section.

#### Scenario: An alarm set on a new entry
- GIVEN a new event in a CalDAV calendar
- WHEN an alarm 15 minutes before is added and the page saved
- THEN the entry carries a `VALARM` with `ACTION:DISPLAY` and `TRIGGER:-PT15M`

#### Scenario: An alarm the page does not show
- GIVEN an event carrying a display alarm 10 minutes before and an email alarm an hour before
- WHEN the display alarm is removed and the page saved
- THEN the email alarm is still there, byte for byte

### Requirement: The add-field chooser names each field with its icon
The add-field chooser of the contact, entry and task pages SHALL be one chooser, listing the fields the item has not got, each with its icon and label, sorted by label; with none it SHALL say so and with one it SHALL open it at once.

#### Scenario: Adding a location
- GIVEN an event with no location
- WHEN the Where section's add button is tapped
- THEN the location's dialog opens at once, the one field left to add there

## MODIFIED Requirements

## REMOVED Requirements
