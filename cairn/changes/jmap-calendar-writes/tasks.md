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
- [x] jscalendarbis renaming at the boundary, both ways: `recurrenceRule`, `calendarAddress`, `organizerCalendarAddress` (found live)
- [x] Traits `writesEvents`, `writesOverrides`, `writesExdates` true for JMAP; JMAP calendars writable by their rights; the 422 refusals and their comments gone
- [x] Ranking: decided against JMAP first for calendars (see the proposal); its comment and the spec say why
- [x] Tests: Rust over a fake JMAP account (create, update, destroy, one occurrence moved, one excluded, an override removed, a stale revision, a stale state through the real coroutine, a refusal for good, a rate limit, a destroy of an event already gone, an object of two events, jscalendarbis both ways)
- [x] Build: `:app:assembleDebug`, `:app:testDebugUnitTest`, `cargo test`, clippy, fmt; `cairn/verify.sh`
- [x] Live check: the Fastmail test sessions serve no calendars; run instead against the local Stalwart 0.16 test container (pimgate's harness), through the bridge's own create, update and destroy over a temporary TCP fake of its calls, removed afterwards: a weekly series created, its listing's revision the create's, its title edited (the patch naming the title alone), one occurrence moved, one excluded, a stale revision refused 412, a missing calendar refused 422, the event deleted and deleted again
- [ ] Device test: a JMAP calendar entry created, edited, one occurrence moved and one deleted, the entry deleted
- [ ] Fold the delta into the specs, log, CHANGELOG
