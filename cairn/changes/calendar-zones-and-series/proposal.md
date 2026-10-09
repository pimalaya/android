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

## Notes from the build

Choices made while building it, where the proposal left room or the code disagreed:

- **The offset crosses whenever the object defines the zone**, not only for a non-IANA `TZID`: the library has no database to tell an IANA name from another, so it resolves every `TZID` its object defines and the Java side prefers the platform's rules for any name it knows (trimmed, any case, behind a libical or Mozilla path, or a Windows name through CLDR). `Zones.zoneFor` names a zone for a `TZID` the platform does not know, for the phone mirror: the IANA zone matching the object's offsets over the given times, else the device's, never UTC.
- **Zones are compared, not only resolved.** `IcalRecurSet` reads every date as written, so a UTC `UNTIL` against a zoned start (which RFC 5545 3.3.10 requires) cut the last occurrence east of UTC, and an `EXDATE` or `RECURRENCE-ID` in another zone named no instance. Each is told in the series' zone first, through the object's `VTIMEZONE`s.
- **A literal time in a gap is read at the offset before it** (RFC 5545 3.3.5), not dropped: `IcalTzOffset::instant` answers a gap with no instant, which is 3.3.10's answer for a generated instance, and the zoned walk already drops those.
- **The page shows the occurrence's dates**, and a save sends only the fields that changed. "All occurrences" moves the series by as many days as the occurrence moved, at the time of day it was given, so moving next week's meeting an hour later moves the series an hour later rather than to next week.
- **This and following**: the old series' `UNTIL` is a second before the earlier of the occurrence's instant and its wall time read as UTC, which bounds the same set for a reader comparing it with local times; a rule already ending sooner keeps its end. The new series keeps the rest of a `COUNT`, its `UNTIL` and its later `EXDATE`s, `RDATE`s and overrides moved with its start; an override at the split occurrence moves along and takes the edit. Of the two staged writes, the create goes first, so a failure between them leaves an occurrence twice rather than none.
- **Graph and Google write the series alone** (io-msgraph's projection pushes the master only; io-gcal's takes one VEVENT, its recurrence with its `EXDATE`s): an override staged there would never reach the server. The page offers "This occurrence" only where the write carries it (`writesOverrides`, `writesExdates` beside `accountLevel` in the account info): both on CalDAV, the delete on Google, neither on Graph. Pushing one instance through those APIs is follow-up work.
- **A new entry's `VTIMEZONE`** covers the zone from the year before the date it is written for: the changes the platform lists, grouped per pair of offsets with `RDATE`s (Morocco's are all listed decades ahead), then the yearly rules as `RRULE`s.
- **Properties are inserted before nested components**: `IcalCst::push` appends after a VALARM, which RFC 5545 3.6.1's grammar does not allow.
- **The agenda asks the bridge for a day more either side** of the week, the window being compared with starts as written, and keeps the week by instant.
- **A moved series' rule moves with its start** (RFC 5545 3.8.5.3): when "All occurrences" or "This and following" puts the start on another day, a weekday moves by the same days (`BYDAY=MO,WE` a day later is `TU,TH`), an ordinal weekday or a single day of the month takes the new start's place in its month (`2TU` to `2WE`, `BYMONTHDAY=15` to `16`), several days of the month or the year move by the same days while none crosses the end of a month or a year, and a single `BYMONTH` or `BYHOUR` takes the new start's. Anything else (`BYSETPOS`, `BYWEEKNO`, several ordinals, a day list crossing a month's end) refuses the save with a message rather than writing a rule that drifts.
- **A spent `COUNT` is never written as `COUNT=0`**: a split past the rule's last counted instance gives the new series no rule (its start and its dates), and the old series keeps its `COUNT`, which already ends before the cut. The split occurrence always remains as the new series' start, so a split never comes out empty.
