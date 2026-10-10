---
cairn: tasks
change: attendee-notifications
---

# Tasks

- [x] Predicate: the phone mirror's organizer rule moved to `calendar.rs`, the mirror on it, an object-level reading beside it
- [x] Bridge: the account's address on `createEvent`, `updateEvent`, `deleteEvent` (Native, PimalayaClient, CalendarEngine, the passes that build one)
- [x] Google: `events.insert` with `sendUpdates=all` for an announced create, import otherwise; `sendUpdates` on every update and delete, master and instance; create and delete behind `GcalWrites`; the calendar's id among the addresses
- [x] JMAP: `sendSchedulingMessages` on create, update and destroy by the rule; a destroy reads the event first
- [x] Graph and CalDAV: verified unchanged, by reading
- [x] Tests: Rust over the fake Google calendar and JMAP account (organized with attendees notified on create, edit, one occurrence, delete; no attendees none; an attendee's own answer none); the predicate's own cases
- [x] Build: `:app:assembleDebug`, `:app:testDebugUnitTest`, `cargo test`, clippy, fmt; `cairn/verify.sh`
- [ ] Device test: a Google and a JMAP meeting with an attendee created, edited, one occurrence moved, deleted, the attendee's mailbox receiving each; an invitation answered from the phone sending nothing new (the insert keeping the object's UID on Google is settled live, see the proposal's note)
- [ ] Fold the delta into spec/calendar.md, log, CHANGELOG
