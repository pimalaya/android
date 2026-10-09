---
cairn: delta
change: jmap-calendar-writes
---

Folds into `spec/calendar.md` and `spec/onboarding.md`, after `jmap-release-readiness` (whose "A JMAP calendar is read only" this removes).

## ADDED Requirements

### Requirement: A JMAP calendar entry is written through CalendarEvent/set
A calendar behind the `jmap://` marker SHALL take creates, edits and deletes through draft-ietf-jmap-calendars `CalendarEvent/set`. An entry's revision SHALL be a hash of its CalendarEvent JSON. The members draft-ietf-jmap-calendars takes from jscalendarbis (`recurrenceRule`, a participant's `calendarAddress`, `organizerCalendarAddress`) SHALL read as and be written from the RFC 8984 ones the conversion carries, a series of more than one rule being refused for good. A create SHALL file the object's one JSCalendar entry in the calendar, under the id the server assigns. An edit SHALL read the event first and be refused on its precondition (412) when the revision moved since it was staged; it SHALL patch only the members that differ between the staged object and the server copy converted to iCalendar and back, `recurrenceOverrides` per recurrence id, and SHALL be sent with `ifInState` set to the state that read answered, a `stateMismatch` being a 412. A delete staged against a revision SHALL be checked the same way; one the server no longer finds SHALL converge. A refusal the server gives for the request itself (`forbidden`, `invalidProperties`, `invalidPatch`, `tooLarge`, `overQuota`, `singleton`, `noSupportedScheduleMethods`) SHALL be refused for good (422); any other SHALL keep the change staged.

#### Scenario: A new entry on a JMAP calendar
- GIVEN a writable JMAP calendar
- WHEN an entry is created and the calendar syncs
- THEN the server holds it in that calendar, and the entry is filed under the id the server gave it

#### Scenario: One occurrence moved
- GIVEN a weekly series on a JMAP calendar
- WHEN one occurrence is moved with "This occurrence" and the calendar syncs
- THEN the event's `recurrenceOverrides` gains that occurrence's patch, and nothing else of the event is sent

#### Scenario: One occurrence deleted
- GIVEN the same series
- WHEN one occurrence is deleted with "This occurrence" and the calendar syncs
- THEN the event's `recurrenceOverrides` names that occurrence `excluded`

#### Scenario: The event moved on the server
- GIVEN an entry edited here and on another client since the last pass
- WHEN the sync pushes the edit
- THEN nothing is written and the edit stays staged

#### Scenario: A property the server refuses
- GIVEN a server answering `invalidProperties` for the edit
- WHEN the sync pushes it
- THEN the edit is refused and shown so, and the calendar's other changes sync

#### Scenario: A server speaking jscalendarbis
- GIVEN a server holding a weekly series under `recurrenceRule`, its attendee under `calendarAddress`
- WHEN the calendar syncs, and the series is edited and pushed
- THEN the agenda shows every occurrence and the attendee's address, and the write names the rule and the attendee the way the server holds them

#### Scenario: Data the app does not carry
- GIVEN a server event with a member the conversion keeps only through its escape hatch
- WHEN its title is edited here and pushed
- THEN the patch names the title alone, and the member is left as the server holds it

## MODIFIED Requirements

### Requirement: The standard setup connects with the best sign-in found
The standard setup SHALL connect a domain without asking how: at Google and Microsoft, with their own APIs (Gmail, Google Calendar, the People API, Graph) signed in through the app's OAuth registration; elsewhere with the best-ranked discovered configuration for that domain, then OAuth over an API token over a password. The ranking SHALL follow what the app can do over each protocol for the domain: JMAP over IMAP and SMTP for mail and over CardDAV for contacts, and CalDAV over JMAP for calendars, since whether a JMAP session serves calendars is known only once signed in and a domain it does not serve is dropped rather than connected over CalDAV. The user SHALL choose only which domains to connect. A domain offering no sign-in at all SHALL be shown switched off and unswitchable, naming the setup that can connect it. An address offering none for any domain SHALL send the flow to the advanced setup rather than to an empty screen. Every credential prompt it opens SHALL name the domains it signs in for, and SHALL say how far along the sequence is whenever that sequence has more than one step.

#### Scenario: A JMAP server with CalDAV beside it
- GIVEN an address whose discovery turns up JMAP and CalDAV
- WHEN the standard setup switches calendars on
- THEN calendars connect over CalDAV

#### Scenario: A JMAP server alone
- GIVEN an address whose discovery turns up JMAP alone, its session serving calendars
- WHEN the standard setup switches calendars on
- THEN calendars connect over JMAP, and take writes

## REMOVED Requirements

### Requirement: A JMAP calendar is read only
Removed: JMAP calendars take writes through `CalendarEvent/set`.
