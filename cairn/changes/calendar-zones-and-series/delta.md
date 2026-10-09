---
cairn: delta
change: calendar-zones-and-series
---

Folds into `spec/calendar.md`.

## ADDED Requirements

### Requirement: The agenda shows entries at the reader's time
The agenda SHALL place and label every occurrence at its instant in the device's time zone: a UTC time as UTC, a zoned time in its zone (an IANA name by the platform's database, a Windows name by the CLDR table, any other by the object's `VTIMEZONE`), a floating time in the device's zone.

#### Scenario: An event from Graph
- GIVEN a Graph event at 08:00 UTC, the device in Europe/Paris in summer
- WHEN the agenda shows its day
- THEN it shows at 10:00

### Requirement: The agenda shows a series as it is
The agenda SHALL place a recurring entry's occurrences as its recurrence set defines them: an occurrence excluded by `EXDATE` or cancelled by an override SHALL NOT show, and an occurrence an override moved SHALL show once, at its new time.

#### Scenario: One occurrence moved
- GIVEN a weekly series whose Tuesday occurrence an override moved to Wednesday
- WHEN that week is shown
- THEN the agenda shows it on Wednesday alone

### Requirement: One occurrence of a series can be changed
The entry page, opened from an occurrence of a series, SHALL ask whether a save or a delete applies to that occurrence or to all of them. One occurrence edited SHALL be written as an override of it, one occurrence deleted as an `EXDATE`.

#### Scenario: From next week on
- GIVEN a weekly series opened from next week's occurrence
- WHEN its start is moved an hour later and "This and following" is picked
- THEN this week keeps the old hour, next week on show the new one, and the next sync sends the shortened series and a new one

#### Scenario: Moving one meeting
- GIVEN a weekly series opened from its Tuesday occurrence
- WHEN its start is moved to Wednesday and "This occurrence" is picked
- THEN that week shows it on Wednesday, the other weeks on Tuesday, and the next sync sends one object carrying the override

## MODIFIED Requirements

### Requirement: A calendar entry can be created
The app SHALL create a calendar entry from the entry page in the store alone: a fresh object carrying the `UID`, `DTSTAMP` and `DTSTART` RFC 5545 section 3.6.1 requires, the start in the device's time zone with the `VTIMEZONE` that zone needs, staged as a pending create. The next sync SHALL PUT it to a resource named after its `UID`, guarded by `If-None-Match: *`.

#### Scenario: A new entry
- GIVEN a writable calendar
- WHEN the agenda's add button is used and the page is saved
- THEN the entry appears in the agenda at once, with no network
- AND the next sync creates it on the server

#### Scenario: The resource already exists
- GIVEN a server already holding that resource
- WHEN the sync pushes the create
- THEN the server refuses it, nothing is overwritten, and the create stays staged

#### Scenario: Several calendars
- GIVEN more than one writable calendar
- WHEN a new entry is started
- THEN the calendar it lands in is asked for, and taken silently when there is only one

### Requirement: A calendar entry can be edited
The app SHALL stage an edit against the ETag the entry was read at, and the agenda SHALL show it at once. The next sync SHALL PUT it guarded by that ETag, so the write is conditioned on the state the edit was made against rather than on whatever arrived since. A date the edit replaces SHALL keep its time zone.

#### Scenario: Editing an entry
- GIVEN an entry open on its page
- WHEN it is saved
- THEN the agenda shows the edit with no network, and the next sync pushes it

#### Scenario: The entry moved under it
- GIVEN an entry another client has edited since it was read
- WHEN the sync pushes the edit
- THEN the server refuses on the precondition and the edit stays staged

#### Scenario: A zoned start
- GIVEN an entry starting at 09:00 Europe/Paris
- WHEN its title is edited and saved
- THEN its start is still 09:00 Europe/Paris

## REMOVED Requirements
