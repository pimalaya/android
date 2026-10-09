---
cairn: delta
change: phone-calendar-mirror
---

Folds into `spec/phone-mirror.md`.

## ADDED Requirements

### Requirement: Calendars show in the phone's Calendar app
An account's calendars SHALL show in the phone's calendar apps when its switch is on, each as a calendar of its own, read only where the collection is. VTODO and VJOURNAL entries SHALL NOT be shown there.

#### Scenario: A first setup
- GIVEN an address with two calendars, set up with the switch on and the permission granted
- WHEN the first sync ends
- THEN the phone's Calendar app lists both calendars and their events

### Requirement: An event edited on the phone comes back whole
An event edited in a calendar app SHALL reach the store as a patch of the fields the phone carries; every other property of the object SHALL survive, and an event the phone did not change SHALL come back byte for byte.

#### Scenario: A property the phone cannot hold
- GIVEN an event carrying an `X-` property and an attachment
- WHEN its title is changed in Google Calendar
- THEN the stored object has the new title, the `X-` property and the attachment

#### Scenario: One occurrence moved
- GIVEN a weekly series
- WHEN one occurrence is moved in a calendar app, this occurrence only
- THEN the stored object carries an override for that occurrence, and the server receives it at the next sync

### Requirement: Reminders fire from the phone
An event's display and audio alarms relative to its start SHALL be projected as reminders, so the phone's calendar app posts them; a reminder changed there SHALL change the alarm.

#### Scenario: Five minutes before
- GIVEN an event with an alarm 5 minutes before its start, in a mirrored calendar
- WHEN the time comes, Pimalaya closed
- THEN the phone's calendar app notifies

## MODIFIED Requirements

## REMOVED Requirements
