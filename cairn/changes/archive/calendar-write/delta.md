---
cairn: delta
change: calendar-write
---

## ADDED Requirements

### Requirement: A calendar entry can be created
The app SHALL create a calendar entry from the entry page: a fresh object carrying the `UID`, `DTSTAMP` and `DTSTART` RFC 5545 section 3.6.1 requires and nothing else, PUT to a resource named after its `UID` and guarded by `If-None-Match: *`.

#### Scenario: A new entry
- GIVEN a writable calendar
- WHEN the agenda's add button is used and the page is saved
- THEN the object is created on the server and appears in the agenda

#### Scenario: The resource already exists
- GIVEN a server already holding that resource
- WHEN the create runs
- THEN the server refuses it and nothing is overwritten

#### Scenario: Several calendars
- GIVEN more than one writable calendar
- WHEN a new entry is started
- THEN the calendar it lands in is asked for, and taken silently when there is only one

### Requirement: A calendar entry can be deleted
The app SHALL delete a calendar entry, guarded by the ETag it was read at, after asking. A delete the server refuses SHALL leave the stored entry alone.

#### Scenario: Deleting an entry
- GIVEN an entry open on its page
- WHEN it is deleted and the server accepts
- THEN it leaves the agenda

#### Scenario: The entry moved under it
- GIVEN an entry another client has edited since it was read
- WHEN it is deleted
- THEN the server refuses on the precondition and the entry stays
