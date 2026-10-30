---
cairn: delta
change: event-alarm-details
---

Folds into spec/calendar.md.

## ADDED Requirements

### Requirement: The all-day switch gives back what it took
Flipping an entry to all-day and back, its dates untouched in between, SHALL give back the dates and times it had; dates edited while all-day SHALL be converted as before.

#### Scenario: On and off
- GIVEN a new event from 14:00 to 15:30
- WHEN all-day is switched on, then off
- THEN it runs from 14:00 to 15:30 again

### Requirement: A new entry is left by going back
The page of an entry not saved yet SHALL offer no delete: going back SHALL leave it unsaved.

#### Scenario: A new entry
- GIVEN the agenda's add button
- WHEN the page opens
- THEN its bar carries no delete

## MODIFIED Requirements

### Requirement: An entry's alarms are the phone's to ring
The page of a CalDAV event SHALL show every alarm of its master component, whatever its action, each saying its type (a display alarm as a notification, an audio one as a sound, an email one as an email, another action as written), when it rings (off the start, off the end, or at a moment), its message, an email's recipients and how it repeats. A row SHALL change its time among presets, its type, its message, and an email's subject and recipients, or remove it; a new alarm SHALL take a type and a time, its message the entry's title, an email one addressed to the account under that title. The app SHALL NOT ring an alarm itself: the phone's calendar provider rings the display and audio alarms the mirror projects, and another client acts on the rest. A save SHALL write the alarms one by one: an alarm kept SHALL stay byte for byte, a changed one SHALL have only what moved rewritten, a dropped one SHALL go, and a new one SHALL carry what RFC 5545 3.6.6 asks of its action (a description; an email one a subject and its recipients too). A repeat SHALL be kept, not edited. Alarms SHALL be the whole entry's: a save changing them alone SHALL ask no scope. A Microsoft or Google entry, a to-do, a journal entry and an entry in conflict SHALL show no alarm section.

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

### Requirement: A calendar entry can be created
The app SHALL create a calendar entry from the entry page in the store alone: a fresh object carrying the `UID`, `DTSTAMP` and `DTSTART` RFC 5545 section 3.6.1 requires and nothing else, staged as a pending create, in the calendar the page's dropdown names: the account's default, else the first writable one, the dropdown in a card of its own on top of the page while creating and shown only when several calendars take writes. The next sync SHALL PUT it to a resource named after its `UID`, guarded by `If-None-Match: *`. A create the server refuses as such, with 403, 409, 412 or 422, SHALL stay staged for the sync that follows and the pass SHALL go on with the calendar's other changes, so one refused item never fails the calendar's pass; a 401, a throttle (a 403 naming a rate limit or a quota) and every other failure SHALL fail the pass as before. A refusal SHALL cross the bridge with its status whichever refusal io-webdav reads it as, a duplicate `UID` (`no-uid-conflict`, `unique-scheduling-object-resource`) included.

#### Scenario: A new entry
- GIVEN a writable calendar
- WHEN the agenda's add button is used and the page is saved
- THEN the entry appears in the agenda at once, with no network
- AND the next sync creates it on the server

#### Scenario: The resource already exists
- GIVEN a server already holding that resource
- WHEN the sync pushes the create
- THEN the server refuses it, nothing is overwritten, and the create stays staged

#### Scenario: One create refused among others
- GIVEN a calendar with two staged creates, the server answering the first with 403 "WebDAV collection already holds a resource with the same UID" or with 409
- WHEN the calendar syncs
- THEN the pass succeeds, the second is created, and the first stays staged

#### Scenario: A refused token
- GIVEN a staged create the server answers with 401
- WHEN the calendar syncs
- THEN the pass fails, so the token is renewed

#### Scenario: Several calendars
- GIVEN more than one writable calendar
- WHEN a new entry is started
- THEN the page opens at once in the default calendar, a dropdown in a card on top naming each calendar with its account
- AND with only one writable calendar there is no dropdown

## REMOVED Requirements
