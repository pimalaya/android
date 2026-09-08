---
cairn: spec
capability: calendar
status: current
---

# Calendar

A calendar is a collection of kind `text/calendar` and an entry is one item in it, stored as the iCalendar text the server sent and never rewritten: what an entry renders as depends on the window being shown, so the recurrence expansion happens at render time and the summary beside it is what an agenda row reads.

Two backends answer: a CalDAV context root, or the draft-ietf-jmap-calendars verbs behind the `jmap://` marker, whose JSCalendar payload is converted at the client boundary so the store keeps one shape.

Every calendar runs io-pimdir's sync. The pass reads one listing per calendar, and it carries the objects themselves, so the enumerate and the fetch are both served from it and neither costs a request; what a pass does is reconcile rather than replace, which is what lets a staged create, edit or delete survive one. A reconcile is followed by an upgrade, which is not optional: a sync that finds the remote content changed drops the body on purpose, and an agenda reads its entries by their body. Incremental listing is not wired: CalDAV's ctag and sync-token rounds are what one would use, so every round is a complete one and reports itself as such.

The entry page is the reading screen and the form at once, the way a contact's is. It edits the stored component rather than the occurrence that was tapped, an override with its own `RECURRENCE-ID` being what changing one date of a series means and not being written yet.

Every write is CalDAV: a JMAP calendar takes `CalendarEvent/set` in JSCalendar, which is the conversion the read path does in the other direction and is not written yet, so it refuses rather than silently doing nothing.

### Requirement: A pass puts back the bodies it dropped
A calendar pass SHALL raise every placement its reconcile left below full back to its body, from the listing the pass already read. An entry SHALL NOT leave the agenda because the server changed it.

#### Scenario: An entry the server changed
- GIVEN a stored entry and a listing carrying a new revision for it
- WHEN the calendar is synced
- THEN the entry is still in the agenda, carrying what the server now holds

#### Scenario: An entry the server no longer holds
- GIVEN a stored entry the listing omits
- WHEN the calendar is synced
- THEN it leaves the agenda

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
