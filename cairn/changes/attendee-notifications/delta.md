---
cairn: delta
change: attendee-notifications
---

Folds into `spec/calendar.md`, after `calendar-occurrence-writes` and `jmap-calendar-writes`.

## ADDED Requirements

### Requirement: A meeting the user organizes is announced to its attendees
A write SHALL be announced to the attendees when the user organizes the event and it has attendees, and SHALL send nothing otherwise. The user organizes an event when its `ORGANIZER` is one of the account's addresses, compared case-insensitively, or when it has attendees and no `ORGANIZER`; this is the rule the phone mirror bumps `SEQUENCE` by. The account's address is the calendar's owner on the phone; on Google the calendar's own id counts as one too. A create SHALL be judged by the object staged, an edit (the series or one occurrence) by the object staged or the server copy it replaces, a delete by the server copy. Per backend:

- CalDAV: nothing is sent by the app; the server schedules implicitly (RFC 6638).
- Graph: nothing is sent by the app; Graph notifies the attendees of the organizer's create, update and delete by itself, and has no option.
- Google: an announced create SHALL be an `events.insert` with `sendUpdates=all`, carrying the object's `UID` as its `iCalUID`, any other create an `events.import`, which notifies nobody. Every update and delete, of the master or of one instance, SHALL name `sendUpdates`: `all` when announced, `none` otherwise.
- JMAP: every `CalendarEvent/set` create, update and destroy SHALL carry `sendSchedulingMessages: true` when announced, and false otherwise.

An attendee's own answer (a `PARTSTAT` change on their `ATTENDEE` in someone else's event) SHALL send nothing new: replying is each server's own flow, which the app does not drive.

#### Scenario: A meeting moved on Google
- GIVEN a Google event the user organizes, with one attendee
- WHEN it is moved and the calendar syncs
- THEN the update names `sendUpdates=all`, and the attendee hears of it

#### Scenario: One occurrence moved on JMAP
- GIVEN a weekly series on a JMAP calendar, organized by the account's address, with one attendee
- WHEN one occurrence is moved with "This occurrence" and the calendar syncs
- THEN the `CalendarEvent/set` carries `sendSchedulingMessages: true`

#### Scenario: A meeting deleted
- GIVEN a Google or JMAP event the user organizes, with attendees
- WHEN it is deleted and the calendar syncs
- THEN the delete is announced, and the attendees receive the cancellation

#### Scenario: An event with no attendees
- GIVEN an event with no attendee
- WHEN it is created, edited or deleted and the calendar syncs
- THEN Google is told `sendUpdates=none` (a create being an import) and JMAP no scheduling messages

#### Scenario: An invitation answered
- GIVEN someone else's event, the user one of its attendees
- WHEN the user's `PARTSTAT` changes and the calendar syncs
- THEN Google is told `sendUpdates=none` and JMAP no scheduling messages

## MODIFIED Requirements

### Requirement: A Google calendar can run over the Calendar API
A calendar connection behind the `google://` marker SHALL list the user's calendar list and read events as iCalendar, a series with its changed and cancelled instances as one entry, those instances being the listed events naming the series as theirs. An entry's revision SHALL be the master's ETag folded with its instances', so an instance edited on Google moves the entry. Every pass SHALL list the calendar in full, and an entry the pass does not hold at its revision SHALL be taken from that listing rather than read again. A write SHALL go to the master with Google's `If-Match`, after the folded revision is checked, and a created event SHALL keep its UID: imported, or inserted under its `iCalUID` when the create is announced to attendees.

#### Scenario: An instance edited on Google
- GIVEN a series whose one occurrence was moved on Google since the last pass
- WHEN the calendar syncs
- THEN the entry carries the moved occurrence, read with the listing

## REMOVED Requirements
