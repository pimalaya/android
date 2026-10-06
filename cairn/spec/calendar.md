---
cairn: spec
capability: calendar
status: current
---

# Calendar

A calendar is a collection of kind `text/calendar` and an entry is one item in it, stored as the iCalendar text the server sent and never rewritten: what an entry renders as depends on the window being shown, so the recurrence expansion happens at render time and the summary beside it is what an agenda row reads.

Four backends answer: a CalDAV context root, the draft-ietf-jmap-calendars verbs behind the `jmap://` marker, Microsoft Graph behind the `msgraph://` one, or the Google Calendar API behind `google://`. The JMAP, Graph and Google payloads are converted at the client boundary, JSCalendar by ical-rs and Graph and Google events by io-msgraph and io-gcal, so the store keeps one shape.

Every calendar runs io-pimdir's sync, and asks what changed. The enumerate is an RFC 6578 `sync-collection` REPORT from the cursor the last pass stored, answering resource names and ETags; the fetch is a `calendar-multiget` (RFC 4791 section 7.9) of the entries the merge asked about. A quiet calendar costs one report and no body. A reconcile is followed by an upgrade, which is not optional: a sync that finds the remote content changed drops the body on purpose, and an agenda reads its entries by their body.

The enumeration takes the whole collection rather than filtering it to VEVENT: a `sync-collection` has no component filter, and the app wanted the others anyway. The expander places a to-do and a journal entry on a day like anything else with a date, and the entry page edits all three; the filter the old listing carried was the one place that disagreed.

A JMAP calendar has no incremental read: draft-ietf-jmap-calendars would answer it through `CalendarEvent/changes`, which is not wired, so it answers a complete round and carries no cursor. A Graph calendar has none either: its event delta runs over a time window only, and an event leaving the window would read as deleted. A server refusing a first `sync-collection` with 400, as Google does (it serves sync tokens at Depth 0 alone), is listed with a `PROPFIND` instead, which carries no token either.

The entry page is the reading screen and the form at once, the way a contact's is. It edits the stored component rather than the occurrence that was tapped, an override with its own `RECURRENCE-ID` being what changing one date of a series means and not being written yet.

Every write is CalDAV or Graph: a JMAP calendar takes `CalendarEvent/set` in JSCalendar, which is the conversion the read path does in the other direction and is not written yet, so it refuses rather than silently doing nothing.

### Requirement: A pass puts back the bodies it dropped
A calendar pass SHALL raise every placement its reconcile left below full back to its body, by reading the entries the merge named. An entry SHALL NOT leave the agenda because the server changed it.

#### Scenario: An entry the server changed
- GIVEN a stored entry the enumerate reports at a new revision
- WHEN the calendar is synced
- THEN the entry is still in the agenda, carrying what the server now holds

#### Scenario: An entry the server no longer holds
- GIVEN a stored entry the enumerate reports vanished
- WHEN the calendar is synced
- THEN it leaves the agenda

### Requirement: The entry page fills only the rows an entry has
The entry page SHALL draw the rows describing the occurrence that was tapped only for an entry opened from one. An entry being composed has none: it is not placed until it is saved, so there is no instance to name and nothing to count down to.

#### Scenario: A new entry
- GIVEN the agenda's add button
- WHEN the page opens on the entry it started
- THEN it draws without the countdown and without naming an occurrence

### Requirement: A calendar entry can be created
The app SHALL create a calendar entry from the entry page in the store alone: a fresh object carrying the `UID`, `DTSTAMP` and `DTSTART` RFC 5545 section 3.6.1 requires and nothing else, staged as a pending create. The next sync SHALL PUT it to a resource named after its `UID`, guarded by `If-None-Match: *`.

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
The app SHALL stage an edit against the ETag the entry was read at, and the agenda SHALL show it at once. The next sync SHALL PUT it guarded by that ETag, so the write is conditioned on the state the edit was made against rather than on whatever arrived since.

#### Scenario: Editing an entry
- GIVEN an entry open on its page
- WHEN it is saved
- THEN the agenda shows the edit with no network, and the next sync pushes it

#### Scenario: The entry moved under it
- GIVEN an entry another client has edited since it was read
- WHEN the sync pushes the edit
- THEN the server refuses on the precondition and the edit stays staged

### Requirement: A calendar entry can be deleted
The app SHALL stage a calendar entry's deletion, after asking, and the entry SHALL leave the agenda at once. The next sync SHALL delete it guarded by the ETag it was read at; a delete the server refuses SHALL leave the removal staged for the sync that follows.

#### Scenario: Deleting an entry
- GIVEN an entry open on its page
- WHEN it is deleted
- THEN it leaves the agenda at once, and the next sync tells the server

#### Scenario: The entry moved under it
- GIVEN an entry another client has edited since it was read
- WHEN the sync pushes the delete
- THEN the server refuses on the precondition and the removal stays staged

#### Scenario: An entry the server has never seen
- GIVEN a new entry that has not been saved
- WHEN it is deleted
- THEN the page closes and nothing is ever sent

### Requirement: A Microsoft calendar runs over Graph
A calendar connection behind the `msgraph://` marker SHALL list the user's calendars and read their events as iCalendar, a series with its exceptions as one entry. Every pass SHALL list the calendar in full. A write SHALL be refused when the event's revision moved since the edit was staged, and a created event SHALL be filed under the id Graph gave it.

#### Scenario: An event edited on Outlook meanwhile
- GIVEN an entry edited here and on Outlook since the last pass
- WHEN the sync pushes the edit
- THEN Graph is not written and the edit stays staged

### Requirement: A Google calendar can run over the Calendar API
A calendar connection behind the `google://` marker SHALL list the user's calendar list and read events as iCalendar, a series with its changed and cancelled instances as one entry, those instances read through the series' `iCalUID`. An entry's revision SHALL be the master's ETag folded with its instances', so an instance edited on Google moves the entry. Every pass SHALL list the calendar in full. A write SHALL go to the master with Google's `If-Match`, after the folded revision is checked, and a created event SHALL be imported so it keeps its UID.

#### Scenario: An instance edited on Google
- GIVEN a series whose one occurrence was moved on Google since the last pass
- WHEN the calendar syncs
- THEN the entry is read again, the moved occurrence with it

### Requirement: The agenda offers the coming days
The agenda's header SHALL carry a strip of the next fourteen days, and pressing one SHALL scroll the agenda to the first entry on or after it.

#### Scenario: Jumping to Friday
- GIVEN entries on Wednesday and Saturday
- WHEN Friday is pressed in the strip
- THEN the agenda scrolls to Saturday's card
