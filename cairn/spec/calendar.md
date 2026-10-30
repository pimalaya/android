---
cairn: spec
capability: calendar
status: current
---

# Calendar

A calendar is a collection of kind `text/calendar` and an entry is one item in it, stored as the iCalendar text the server sent and never rewritten: what an entry renders as depends on the window being shown, so the recurrence expansion happens at render time and the summary beside it is what an agenda row reads.

Four backends answer: a CalDAV context root, the draft-ietf-jmap-calendars verbs behind the `jmap://` marker, Microsoft Graph behind the `msgraph://` one, or the Google Calendar API behind `google://`. The JMAP, Graph and Google payloads are converted at the client boundary, JSCalendar by ical-rs and Graph and Google events by io-msgraph and io-gcal, so the store keeps one shape.

Every calendar runs io-pimdir's sync, and asks what changed. The enumerate is an RFC 6578 `sync-collection` REPORT from the cursor the last pass stored, answering resource names and ETags; the fetch is a `calendar-multiget` (RFC 4791 section 7.9) of the entries the merge asked about. A quiet calendar costs one report and no body. A reconcile is followed by an upgrade, which is not optional: a sync that finds the remote content changed drops the body on purpose, and an agenda reads its entries by their body.

Graph and Google have no multiget: a Graph pass reads the entries it names by `$batch`, 20 requests to a call, and a Google pass takes them from the complete listing it made anyway, as a JMAP one does. An account's calendars run side by side, each on its own connection, the store keeping one writer.

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
The app SHALL stage a calendar entry's deletion, after asking, and the entry SHALL leave the agenda at once. The next sync SHALL delete it guarded by the ETag it was read at; a delete the server refuses SHALL leave the removal staged for the sync that follows. A delete finding the entry already gone SHALL count as done: a 404, or a 412 when the server, read back, holds no entry of that name (an `If-Match` on an object gone being false, RFC 9110 section 13.1.1).

#### Scenario: Deleting an entry
- GIVEN an entry open on its page
- WHEN it is deleted
- THEN it leaves the agenda at once, and the next sync tells the server

#### Scenario: The entry moved under it
- GIVEN an entry another client has edited since it was read
- WHEN the sync pushes the delete
- THEN the server refuses on the precondition and the removal stays staged

#### Scenario: The entry already gone
- GIVEN a deleted entry another client deleted on the server after the sync listed it, on a server answering 412 to the guarded delete of an object gone
- WHEN the sync pushes the delete
- THEN the removal is done and nothing stays staged

#### Scenario: An entry the server has never seen
- GIVEN a new entry that has not been saved
- WHEN it is deleted
- THEN the page closes and nothing is ever sent

### Requirement: A Microsoft calendar runs over Graph
A calendar connection behind the `msgraph://` marker SHALL list the user's calendars and read their events as iCalendar, a series with its exceptions as one entry. Every pass SHALL list the calendar in full. The entries a pass reads SHALL be read 20 requests to a `$batch`, the events and then each series' instances, a request the batch could not serve being sent again on its own; an event Graph no longer holds when it is read SHALL be left out. A write SHALL be refused when the event's revision moved since the edit was staged, and a created event SHALL be filed under the id Graph gave it.

#### Scenario: An event edited on Outlook meanwhile
- GIVEN an entry edited here and on Outlook since the last pass
- WHEN the sync pushes the edit
- THEN Graph is not written and the edit stays staged

#### Scenario: A first sync
- GIVEN a Graph calendar of 40 events, five of them series
- WHEN it is synced for the first time
- THEN its events are read in two `$batch` calls and its series' instances in one, rather than one request an event and one more per window of a series

#### Scenario: An event deleted on Outlook since the listing
- GIVEN an event listed and then deleted on Outlook before it is read
- WHEN the pass reads it
- THEN it is left out and the rest of the calendar is stored

### Requirement: A Google calendar can run over the Calendar API
A calendar connection behind the `google://` marker SHALL list the user's calendar list and read events as iCalendar, a series with its changed and cancelled instances as one entry, those instances being the listed events naming the series as theirs. An entry's revision SHALL be the master's ETag folded with its instances', so an instance edited on Google moves the entry. Every pass SHALL list the calendar in full, and an entry the pass does not hold at its revision SHALL be taken from that listing rather than read again. A write SHALL go to the master with Google's `If-Match`, after the folded revision is checked, and a created event SHALL be imported so it keeps its UID.

#### Scenario: An instance edited on Google
- GIVEN a series whose one occurrence was moved on Google since the last pass
- WHEN the calendar syncs
- THEN the entry carries the moved occurrence, read with the listing

### Requirement: The agenda offers the week
The agenda's header SHALL carry the month and year of the shown week, its number and arrows to the weeks either side, over one card of that week's seven days, today in the accent. Pressing the number SHALL bring the card back to this week. Pressing a day SHALL narrow the agenda to that day, the day on a filled disc, and pressing it again SHALL widen it back to the shown week. With no day pressed, the agenda SHALL list the shown week's seven days alone, from the locale's first weekday, this week the same as any other.

#### Scenario: Only Friday
- GIVEN entries on Wednesday, Friday and Saturday, today being Tuesday
- WHEN Friday is pressed in the week
- THEN the agenda shows Friday's entries alone
- AND pressing Friday again shows all three days

#### Scenario: Next week
- GIVEN this week's card
- WHEN the next arrow is pressed
- THEN the card shows next week's days and number, and the agenda that week's entries alone

#### Scenario: This week
- GIVEN entries on this week's Monday, today and in two weeks, today being Wednesday
- WHEN the agenda shows this week with no day pressed
- THEN it lists Monday's and today's entries, and not the one in two weeks

### Requirement: An account's calendars sync side by side
A calendar pass SHALL run an account's calendars concurrently on a pool of three connections, each worker on a connection no other worker uses while it runs, the workers taking the calendars in the order the account lists them. Every storage load, lookup and write SHALL be answered by one writer at a time, so only the network overlaps. A calendar that fails SHALL leave the others running, the pass reporting the first failure. A refused token SHALL be renewed once for the account, whichever worker met it.

#### Scenario: Three calendars
- GIVEN an account of three calendars never synced
- WHEN the calendar tab's first sync runs
- THEN the three are listed at once, and the pass takes about the longest of them rather than their sum

#### Scenario: One calendar refused
- GIVEN a pass over three calendars, one of which the server refuses
- WHEN the pass runs
- THEN the other two are stored, and the pass reports the refusal

### Requirement: Each calendar page's time is logged
A calendar pass SHALL log, page by page, how many entries a listing named and the time spent on the network, on the JSON this side reads and writes, in the engine, and in the store's loads and writes; and, for each account's run, its wall time against the network time summed over its calendars.

#### Scenario: A pass's gain
- GIVEN a debug build
- WHEN an account's calendars have all landed
- THEN the log names how many ran on how many connections, the wall time and the network time summed

### Requirement: Expansion is bounded whatever the rule
An expansion SHALL walk at most 200 000 occurrences of a series, placed or not, and stop after 2 s for the whole object; a this-and-following split SHALL count at most 200 000 occurrences of a `COUNT`. A Graph series SHALL be read for exceptions within five years either side of today, bounded or not.

#### Scenario: A secondly series from 1970
- GIVEN an event `RRULE:FREQ=SECONDLY` starting in 1970
- WHEN the agenda shows January 2026
- THEN it answers within the bound, placing nothing

### Requirement: The phone's calendars take bounded data
The phone mirror SHALL list a secondly or minutely series instance by instance rather than hand its rule to the provider, and SHALL carry at most 5 reminders an event, 1024 characters of title and of location and 16 384 of description, cut between characters, the store keeping the whole text.

#### Scenario: Fifty alarms
- GIVEN an event with 50 `VALARM`s
- WHEN it is mirrored to the phone
- THEN the phone's event carries 5 reminders

### Requirement: A Graph event is created once
A Graph event create SHALL carry a `transactionId`, the hex of the first 16 bytes of the SHA-256 of the iCalendar object, the same for every attempt of one staged create, so a create sent again after a lost answer lands on the event the first made and invites nobody twice.

#### Scenario: A create whose answer was lost
- GIVEN an event with attendees created on Graph, the answer lost
- WHEN the next pass pushes the staged create again
- THEN it names the same transaction, and Graph keeps one event

### Requirement: The agenda builds off the main thread
The agenda SHALL read and expand its entries on a thread of its own and draw the result on the main thread, the last reload asked winning. An entry's expansion SHALL be kept while the shown week and its object stay the same.

#### Scenario: Coming back to the app
- GIVEN 10k stored entries
- WHEN the app returns to the foreground
- THEN the screen stays responsive while the agenda rebuilds, and only entries a sync changed are expanded again

### Requirement: Calendar accounts stay out of contacts apps
The Android accounts holding the phone's calendars SHALL be of a type of their own, bound to the calendar adapter alone, so no contacts app lists them. Calendar accounts of the books' type left by an earlier build SHALL be migrated: their calendars' phone pass first, then the account removed and recreated under the calendar type.

#### Scenario: Picking where a new contact goes
- GIVEN an address whose books and calendars both show on the phone
- WHEN the Contacts app lists the accounts a contact can be saved into
- THEN it lists the books' accounts and no calendar account

### Requirement: An agenda to-do opens on the task page
A dated to-do tapped in the agenda SHALL open on the task page, which goes back to the agenda. A conflicted one, or one that does not read as a task, SHALL keep the entry page.

#### Scenario: A due to-do
- GIVEN a to-do due on Friday
- WHEN its row is tapped in Friday's agenda
- THEN the task page opens, and back returns to the agenda

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

### Requirement: The add-field chooser names each field with its icon
The add-field chooser of the contact, entry and task pages SHALL be one chooser, listing the fields the item has not got, each with its icon and label, sorted by label; with none it SHALL say so and with one it SHALL open it at once.

#### Scenario: Adding a location
- GIVEN an event with no location
- WHEN the Where section's add button is tapped
- THEN the location's dialog opens at once, the one field left to add there

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

### Requirement: The entry page lays out four sections
The entry page SHALL show, in this order, Calendar (the dropdown while a new entry may go to several calendars, else the entry's calendar and account), When, Details and Alarms (where the page edits alarms), then the people, conflicts and links an entry has; the bar SHALL carry no add button. When SHALL show all-day, the start and the end (an event) or the due date (a to-do) always, with no add button; Details SHALL show the title always and every other field once set or added from its header's add button. A date picker SHALL offer to clear its date, every date but an event's start, which RFC 5545 3.6.1 requires.

#### Scenario: A new event
- GIVEN the agenda's add button
- WHEN the page opens
- THEN it shows Calendar, When with its start and end, Details with its title, and Alarms

#### Scenario: Clearing an end
- GIVEN an event with an end
- WHEN its end's date picker is cleared and the page saved
- THEN the entry carries no `DTEND`

#### Scenario: An event's start
- GIVEN an event
- WHEN its start's date picker opens
- THEN it offers no Clear
