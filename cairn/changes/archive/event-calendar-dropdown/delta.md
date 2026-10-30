---
cairn: delta
change: event-calendar-dropdown
---

Folds into spec/calendar.md.

## ADDED Requirements

## MODIFIED Requirements

### Requirement: A calendar entry can be created
The app SHALL create a calendar entry from the entry page in the store alone: a fresh object carrying the `UID`, `DTSTAMP` and `DTSTART` RFC 5545 section 3.6.1 requires and nothing else, staged as a pending create, in the calendar the page's dropdown names: the account's default, else the first writable one, the dropdown on top of the page while creating and shown only when several calendars take writes. The next sync SHALL PUT it to a resource named after its `UID`, guarded by `If-None-Match: *`. A create the server refuses as such, with 403, 409, 412 or 422, SHALL stay staged for the sync that follows and the pass SHALL go on with the calendar's other changes, so one refused item never fails the calendar's pass; a 401, a throttle (a 403 naming a rate limit or a quota) and every other failure SHALL fail the pass as before. A refusal SHALL cross the bridge with its status whichever refusal io-webdav reads it as, a duplicate `UID` (`no-uid-conflict`, `unique-scheduling-object-resource`) included.

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
- THEN the page opens at once in the default calendar, a dropdown on top naming each calendar with its account
- AND with only one writable calendar there is no dropdown

## REMOVED Requirements
