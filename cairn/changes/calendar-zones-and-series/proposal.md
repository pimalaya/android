---
cairn: change
id: calendar-zones-and-series
status: active
created: 2026-10-09
---

# Calendar: time zones and recurring series, as RFC 5545 means them

## Why

Two release blockers of docs/production-review.md, and the ground the phone calendar mirror (`phone-calendar-mirror`) stands on:

- **The agenda ignores time zones.** Expansion is civil by design (`rust/src/calendar.rs`), which is right: RFC 5545 recurs on the wall clock of `DTSTART`. But nothing ever converts that civil time to the reader's: `CalendarList.stampOf` reads every stamp as if it were in the device's zone, dropping `Z` and `TZID`. A Graph or Google event, which arrives in UTC, shows hours off; a meeting at 09:00 New York shows at 09:00 for a reader in Paris.
- **Edits lose zones, new entries have none.** `write`'s `push_date` replaces a date without its `TZID`, so editing anything about a zoned event's dates makes it floating; `create` writes a floating `DTSTART`. A floating event follows whichever zone the reading device is in, which no server or other client takes as meant.
- **A series renders wrong.** `expand` walks each component on its own rule: an occurrence an `EXDATE` excludes still shows, and an occurrence an override (`RECURRENCE-ID`) moved shows twice, at its old and its new time.
- **Editing one occurrence edits the whole series.** The entry page edits the master component: there is no way to change or delete one occurrence.

The phone mirror needs every one of these: the provider stores instants, and it edits and cancels single occurrences.

## What

### Where zones are resolved

The decision of the plan of record holds: no time-zone database in the native library (docs/pimalaya-android-plan.md, "The feature must not own timezone data"). Android ships the IANA database and keeps it current; ical-rs resolves what that database cannot name, from the object's own `VTIMEZONE`, with no database at all (`IcalTz`). So:

- **Rust** expands civil, as now, and hands each occurrence over with its zone: `utc`, `floating`, or a `TZID` with, when the `TZID` is not an IANA name, the offset ical-rs resolved from the object's `VTIMEZONE` (`IcalTz::resolve`, gaps and folds answered by RFC 5545 3.3.5 through `IcalTzOffset::instant`).
- **Java** turns that into an instant: an IANA `TZID` through `java.time.ZoneId`, a Windows `TZID` (Graph and Exchange write them) through the CLDR `windowsZones` table to its IANA zone, a resolved offset as given, `utc` as UTC, `floating` in the device's zone. The agenda then places and labels occurrences in the device's zone.

### Writing zones

- `write` keeps the `TZID` and `VALUE` of a date it replaces, unless the edit names a zone of its own.
- `create` writes the start in the device's zone, as an IANA `TZID`, with the `VTIMEZONE` RFC 5545 3.2.19 wants beside it. Built in Java from `java.time.zone.ZoneRules` (the current era's standard and daylight observances, each an `RRULE` from its `ZoneOffsetTransitionRule`), passed to Rust to insert, so the library stays database-free.
- An all-day entry stays a `DATE`, zoneless, as RFC 5545 means it.

### Series

- `expand` composes the recurrence set with ical-rs's `IcalRecurSet::of_uid` over the object's components: `RRULE`, `RDATE`, `EXDATE`, overrides and `RANGE=THISANDFUTURE`, an occurrence carrying its identity (the time the rule placed it at) and its start (where an override moved it).
- The entry page, opened from an occurrence of a series, asks "This occurrence", "This and following" or "All occurrences" on save and on delete:
  - **This occurrence, edited:** an override component, same `UID`, `RECURRENCE-ID` the occurrence's identity, carrying the edited fields over a copy of the master's; an existing override is patched instead.
  - **This occurrence, deleted:** an `EXDATE` on the master, the shape every server reads; an existing override of it goes.
  - **All occurrences:** the master, as today.
- **This and following** (agreed 2026-10-09: the full feature): the series is split, the shape every server and calendar app reads, rather than written as `RANGE=THISANDFUTURE`, which many servers refuse. The master's rule gains an `UNTIL` just before the occurrence (its `COUNT` recomputed into an `UNTIL` when it had one), its overrides and `EXDATE`s past that point move to a new object with a fresh `UID`, starting at the occurrence and carrying the edit and the rest of the rule. Two staged writes: the master edited, the new object created. Deleting this and following is the first half alone. A stored `THISANDFUTURE` override, written by another client, is still read and rendered by `IcalRecurSet`.

The occurrence is already known to the page (it was opened from one, `EventView`), and the whole object, overrides included, is one item, so the edit is one staged write as now.
