---
cairn: tasks
change: jmap-calendar-writes
---

# Tasks

- [x] io-jmap: `CalendarEvent/set` coroutine, typed set errors with `Display`, `ifInState`, `sendSchedulingMessages`, `JmapClientStd::calendar_event_set`; `JmapMethodError::StateMismatch`; `isOrigin` never serialized; a `/set` answer with no `newState` read as empty (found live); tests over canned responses; docs; CHANGELOG [Unreleased]; its gates
- [x] Bridge: io-jmap through a `[patch.crates-io]` path entry
- [x] Revision: a JMAP event's revision is a hash of its JSON
- [x] Create, update, destroy over `CalendarEvent/set`; the read-before-write check and `ifInState`; refusals mapped to 422 and 412
- [x] Patch against the server copy converted the app's way; `recurrenceOverrides` per recurrence id
- [x] jscalendarbis renaming at the boundary, both ways: `recurrenceRule`, `calendarAddress`, `organizerCalendarAddress` (found live); replaced by ical-rs 0.6.1's JSCalendar 2.0 conversion (`to_jscalendar_as`, `from_jscalendar` reading both), the renaming deleted, `method` still dropped, no `version` sent, a series of several rules still refused for good (Stalwart drops the hatch a second rule would ride)
- [x] Traits `writesEvents`, `writesOverrides`, `writesExdates` true for JMAP; JMAP calendars writable by their rights; the 422 refusals and their comments gone
- [x] Ranking: decided against JMAP first for calendars (see the proposal); its comment and the spec say why
- [x] Tests: Rust over a fake JMAP account (create, update, destroy, one occurrence moved, one excluded, an override removed, a stale revision, a stale state through the real coroutine, a refusal for good, a rate limit, a destroy of an event already gone, an object of two events, JSCalendar 2.0 both ways, no `method` or `version` written, a series of two rules refused on create and edit with no request sent)
- [x] Build: `:app:assembleDebug`, `:app:testDebugUnitTest`, `cargo test`, clippy, fmt; `cairn/verify.sh`
- [x] Live check: the Fastmail test sessions serve no calendars; run instead against the local Stalwart 0.16 test container (pimgate's harness), through the bridge's own create, update and destroy over a temporary TCP fake of its calls, removed afterwards: a weekly series created, its listing's revision the create's, its title edited (the patch naming the title alone), one occurrence moved, one excluded, a stale revision refused 412, a missing calendar refused 422, the event deleted and deleted again
- [x] Live check after ical-rs 0.6.1, the same container and harness (temporary test removed afterwards), scheduling off: a weekly series with an attendee and an excluded occurrence created as JSCalendar 2.0 and listed back with its `RRULE`, `EXDATE`, `ORGANIZER` and attendee address, its revision the create's; the title edit patched `title` alone; one more occurrence excluded and one moved, each patching its `recurrenceOverrides/<id>` alone; a stale revision refused 412; deleted, and deleted again. Stalwart refuses a `version` member (`invalidProperties`) and drops the `iCalendar` hatch a second rule rides (a probe create kept one rule), hence the refusal kept
- [ ] Device test: a JMAP calendar entry created, edited, one occurrence moved and one deleted, the entry deleted
- [ ] Fold the delta into the specs, log, CHANGELOG
