---
cairn: change
id: jmap-calendar-writes
status: active
created: 2026-10-09
---

# JMAP calendars writable: create, edit, delete and single occurrences over `CalendarEvent/set`

## Why

`jmap-release-readiness` made JMAP calendars read only: io-jmap had no `CalendarEvent/set`, so the `writesEvents` trait is false for JMAP, the three write verbs refuse with 422, the entry page hides its save and delete, and the standard setup ranks CalDAV over JMAP for calendars. A Stalwart user with no CalDAV discovered gets a calendar the app can only read, and the phone's calendar app cannot edit it either.

io-jmap left `CalendarEvent/set` out because nothing wrote (docs/jmap-calendars-plan.md in io-jmap, "a `set` coroutine with no caller is untested surface"). This change is that caller, so the reason no longer holds.

## What

- **io-jmap**: `CalendarEvent/set` (draft-ietf-jmap-calendars-27 section 5.9): create, update by PatchObject (a full replacement being a patch naming every property), destroy, `ifInState`, `sendSchedulingMessages`, the per-object errors typed (RFC 8620 section 5.3 plus the draft's `noSupportedScheduleMethods`), `Display` on them as 0.5.0 gave the others. The RFC 8620 section 5.3 `stateMismatch` method error gains its variant in `JmapMethodError`, read as `Unknown` until now, which an `ifInState` guard cannot do without. `JmapCalendarEvent` no longer serializes the server-set `isOrigin`, which a create must not carry (RFC 8620 section 5.3).
- **Revision**: a JMAP event's revision becomes a hash of its CalendarEvent JSON, as a JMAP card's already is, so the engine stages an edit against it and the push can tell an event that moved.
- **Create**: `CalendarEvent/set` create of the staged object's one JSCalendar entry (ical-rs `to_jscalendar_as` at JSCalendar 2.0, the Group's single entry taken out, its `method` dropped) into the calendar's id; the server-assigned id becomes the handle, the event read back for its revision.
- **Update**: the event read first (`CalendarEvent/get`), refused 412 when its revision moved since the edit was staged; the patch is the difference between the staged object and the server copy *as the app sees it* (the server's JSCalendar converted to iCalendar and back, the same path the staged object took), top-level members replaced whole and `recurrenceOverrides` patched per recurrence id; sent with `ifInState` set to the state that read answered, so nothing lands between the check and the write where the server honours it. A `stateMismatch` is a 412.
- **Destroy**: the same read and check when the delete was staged against a revision, then the destroy under `ifInState`; `notFound` converged.
- **Single occurrences**: an override and an `EXDATE` travel inside the event, as `recurrenceOverrides` entries (an exclusion as `excluded: true`), so they need no write of their own: `writesOverrides` and `writesExdates` become true for JMAP with `writesEvents`.
- **Refusals**: `forbidden`, `invalidProperties`, `invalidPatch`, `tooLarge`, `overQuota`, `singleton` and `noSupportedScheduleMethods` are refused for good (422) through the existing refusal path; `notFound` on an update, `rateLimit`, `willDestroy`, `stateMismatch` and anything unknown are 412, the edit kept staged.
- **Listing**: JMAP calendars list writable again by their rights; the entry page and the agenda's add button offer them.
- **JSCalendar 2.0 at the boundary**: draft-ietf-jmap-calendars-27 builds on draft-ietf-calext-jscalendarbis (JSCalendar 2.0). The local Stalwart (0.16) refuses `recurrenceRules` (`invalidProperties`) and silently drops a participant carrying `sendTo` with no `calendarAddress`, and ical-rs 0.6.0 converted RFC 8984 only, so the bridge first renamed the three members that matter both ways around it. ical-rs 0.6.1 writes JSCalendar 2.0 (`to_jscalendar_as`) and reads both versions (`from_jscalendar`), so the renaming is gone: an entry is ical-rs's 2.0 one less its `method` (2.0 puts the calendar's `METHOD` on every entry, a stored event carries none), and a listed payload goes to ical-rs as the server sent it. On read this also fixes a series listed as its first occurrence alone, which the read path did since JMAP calendars landed. A series of several rules stays refused for good (422) before any request: 2.0 holds one `recurrenceRule`, and ical-rs keeps the others in the `iCalendar` hatch, which Stalwart drops (verified live 2026-10-10), so the write would lose them silently. The read side takes whatever the server sends. No `version` is sent: Stalwart refuses one (`invalidProperties`, verified live) and the draft has the server set it.
- **Ranking stays**: CalDAV over JMAP for calendars when discovery finds both. A session's capabilities are only known once signed in, and the probe drops a domain the session does not serve rather than falling back to CalDAV, so ranking JMAP first would cost calendars to every account whose session does not serve them (the Fastmail test sessions serve none). JMAP calendars are used, and now written, wherever CalDAV is not discovered.

## Round-trip safety

The store keeps iCalendar; a write converts it to JSCalendar. ical-rs claims both directions lossless through escape hatches (`iCalendar` and `JSPROP`, `JSID` keeping collection keys), with three normalisations (`UNTIL`'s zone, `DTEND` as a duration, ordering). The patch is diffed against the server copy converted through the same path, so a member the conversion cannot carry, or normalises, compares equal on both sides and is never sent: the write cannot clear server-side data the app never had, only what the edit changed. What can still be lost: a top-level member the edit touches is replaced whole, so a part of it the conversion normalised goes back normalised; an override that changed is replaced whole for the same reason (draft section 5.9.1 also rules out patching inside an override's PatchObject when a `null` is involved). A create sends the whole entry, the calendar-level hatch (a `VTIMEZONE`) left behind since JMAP names zones. Both sides of the diff take the same 2.0 conversion, so it is symmetric too; what 2.0 obsoletes or reserves ical-rs never writes, keeping it in the `iCalendar` hatch. Stalwart keeps none of the `iCalendar` hatch: a create carrying X- properties lands without them.

## Found on the way

- **io-jmap**: Stalwart answers a `/set` that changed nothing without `newState`, which RFC 8620 section 5.3 requires; every `Foo/set` failed to parse it, and now reads it as empty.
- **Stalwart 0.16** ignores `ifInState` (a stale state still writes) and answers a malformed one with a request-level 400; the read-before-write revision check is what guards there.
- **Stalwart 0.16** adds a Participant of its own for the account's address, with no roles, beside the organizer's owner Participant the app wrote, so an organized event lists back with an `ATTENDEE` of the user's address (seen live 2026-10-10).

## Out of scope

- `CalendarEvent/changes` incremental reads; server-side recurrence expansion.
- `Calendar/set`, `CalendarEvent/copy`, `CalendarEventNotification/*`, `ParticipantIdentity/*`.
- Scheduling: this change leaves `sendSchedulingMessages` false; `attendee-notifications` sets it on the writes of a meeting the user organizes. RSVP and the draft's per-user properties for `isOrigin: false` events stay out.
- A fallback from a JMAP calendar the session does not serve to a discovered CalDAV one in the connection flow.
