---
cairn: delta
change: calendar-sync-speed
---

## ADDED Requirements

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

## MODIFIED Requirements

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

## REMOVED Requirements
