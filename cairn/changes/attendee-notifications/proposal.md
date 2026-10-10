---
cairn: change
id: attendee-notifications
status: active
created: 2026-10-10
---

# Attendees hear of a meeting the user organizes, on Google and JMAP too

## Why

A meeting the user organizes, edited in the app or in a calendar app through the phone mirror, reaches its attendees on two backends out of four. A CalDAV server schedules implicitly (RFC 6638): it mails the attendees of an organizer's write by itself. Graph does the same for the organizer's calendar. Google sends nothing unless a write names `sendUpdates`, which the app never does, and a create is an `events.import`, which never invites anyone. JMAP sends nothing unless `CalendarEvent/set` carries `sendSchedulingMessages: true`, and `jmap-calendar-writes` left it false. A user moving a meeting on Google or JMAP believes the attendees know, and they do not.

## What

The rule (the user's decision, 2026-10-10): a write is announced when the user organizes the event and it has attendees; otherwise it sends nothing. The user organizes the event when its `ORGANIZER` is one of the account's addresses, compared case-insensitively, or when it has attendees and no `ORGANIZER` (the server fills the owner). This is the predicate the phone mirror already bumps `SEQUENCE` by (docs/calendar-mapping.md), moved from `calendar/phone.rs` to `calendar.rs` so the clients reach it, with one object-level reading beside it for an iCalendar text (any scheduled component organized with attendees).

- **Which object**: a create is announced by the staged object; an edit (series or one occurrence) by the staged object or the server copy it replaces, so an organizer taking every attendee off still cancels them; a delete by the server copy.
- **The addresses**: the account's address, the one the phone mirror passes as the calendar's owner (the calendar row's `OWNER_ACCOUNT`, the account's email), crosses the bridge on the three write verbs. On Google the calendar's own id counts as one besides: an event created on a secondary calendar is organized by that calendar (`organizer.email` is its id), so an edit of it would otherwise read as someone else's.
- **Google**: an announced create is an `events.insert` with `sendUpdates=all`, its `iCalUID` in the body (the Calendar API takes one at creation in place of an `id`); any other stays an `events.import`, which notifies nobody and has no such parameter. Every update (master and instance) and delete (whole event and instance) names `sendUpdates`: `all` when announced, `none` otherwise, explicitly. The app writes no `events.move` (a relocation is refused before any write, pimdir SYNC section 4) and no `events.patch`. A whole-event create and delete move behind the `GcalWrites` seam the update already runs through, and a delete reads the entry first even when unguarded, since the decision needs the copy it removes.
- **JMAP**: `sendSchedulingMessages: true` on an announced create, update or destroy; false (left out) otherwise. A destroy reads the event first even when unguarded, for the same reason; one gone already converges without a write.
- **Graph, CalDAV**: unchanged. CalDAV scheduling is the server's, implicit on every write by the organizer (RFC 6638 section 3.2). Graph has no option: Microsoft's documentation of the event endpoints says creating a meeting sends the invitations and deleting one on the organizer's calendar sends the cancellations, and an organizer's update reaches the attendees the same way; io-msgraph documents nothing of it and exposes no option either.

## An attendee's own answer

An edit of someone else's event (the user an `ATTENDEE`, changing their `PARTSTAT`) is not announced: `sendUpdates=none` on Google, no `sendSchedulingMessages` on JMAP. Replying is a flow of its own on each server, and the flags here are the wrong tool for it: Google answers through the attendee's `responseStatus` on its own terms, and `sendSchedulingMessages` on a non-origin event sends an iTIP `REPLY` to the organizer (draft-ietf-jmap-calendars section 5.9), which needs the draft's per-user properties and `ParticipantIdentity/*` to be done right. Out of scope; such an edit sends nothing new.

## Out of scope

- RSVP: answering an invitation from the app or the phone mirror, on any backend.
- `ParticipantIdentity/get` as the JMAP source of the user's addresses, and Gmail send-as aliases on Google: the account's address (and the Google calendar's id) are the addresses, as for the mirror.
- `sendUpdates=externalOnly`.

## Notes from the build

NOTE: the 2026-10-06 log entry (google-apis) says an `events.insert` would replace the UID an import keeps. It does not: io-gcal's live test `insert_keeps_ical_uid` (2026-10-10, Workspace test account) inserted an event carrying an `iCalUID` with `sendUpdates=all`, read the same `iCalUID` back from the insert reply and from `events.get`, and found the event by `events.list?iCalUID=`. An announced create through insert keeps the object's identity.
