# Android calendar to iCalendar mapping

The contract for the calendar phone mirror (cairn change `phone-calendar-mirror`): which `CalendarContract` columns map to which iCalendar properties, in both directions, what stays lossy, and the round-trip rules. The contacts twin is docs/contacts-mapping.md, and every rule there that is not about vCard holds here.

## Architecture

```
CalendarContract rows  <-- Java (CalendarMapping) -->  phone model  <-- Rust (projectEvent / applyEvent) -->  iCalendar object
```

- **Java** reads and writes the provider and converts rows to and from the phone model, pure and JVM-tested.
- **Rust** projects the stored object onto the phone model and patches an edited model back onto it through the ical-rs CST, so everything the model does not carry survives byte for byte.
- The stored object is the record. A phone edit never regenerates it: the fetch boundary merges the phone's model over the projection of the phone base (a field is taken from the phone only when it differs from what the base projects) and applies that as a patch.

One stored item is one iCalendar object: one `UID`, a master component and the components overriding its instances. On the phone it is one master `Events` row plus one row per override, with their `Reminders` and `Attendees` rows. VTODO and VJOURNAL are never projected: the provider holds events only.

## Calendar level

One Android account per Pimalaya account (type `org.pimalaya`, named by the address), syncable for `com.android.calendar` alone. One `Calendars` row per mirrored calendar.

| Calendars column | Value |
|---|---|
| `_SYNC_ID` | the collection id |
| `NAME`, `CALENDAR_DISPLAY_NAME` | the collection's name |
| `CALENDAR_COLOR` | the collection's colour, else the account's avatar hue |
| `CALENDAR_ACCESS_LEVEL` | `CAL_ACCESS_OWNER` when writable, `CAL_ACCESS_READ` otherwise |
| `OWNER_ACCOUNT` | the address (calendar apps compare it to `ORGANIZER` to know whether the user organizes) |
| `SYNC_EVENTS`, `VISIBLE` | 1 |
| `CALENDAR_TIME_ZONE` | the collection's `calendar-timezone` when it has one, else none |
| `ALLOWED_REMINDERS` | `1` (alert) |
| `ALLOWED_AVAILABILITY` | `0,1,2` (busy, free, tentative) |
| `ALLOWED_ATTENDEE_TYPES` | `1,2,3` (required, optional, resource) |

## Event level

| Events column | iCalendar | Notes |
|---|---|---|
| `_SYNC_ID` | the item's handle | stamped onto rows a calendar app created, at enumerate time |
| `UID_2445` | `UID` | |
| `SYNC_DATA1` | revision token | ours, stamped at convergence; with `DIRTY`, the change signal (the contacts' SYNC1 scheme) |
| `DIRTY`, `DELETED` | | set by calendar apps, never by our writes (`CALLER_IS_SYNCADAPTER`) |
| `TITLE` | `SUMMARY` | |
| `DESCRIPTION` | `DESCRIPTION` | |
| `EVENT_LOCATION` | `LOCATION` | |
| `DTSTART` + `EVENT_TIMEZONE` | `DTSTART` with its `TZID` | milliseconds UTC plus an IANA id; see zones |
| `DTEND` + `EVENT_END_TIMEZONE` | `DTEND` | single events only |
| `DURATION` | `DURATION`, or `DTEND - DTSTART` | recurring events only: the provider requires `DURATION` and a null `DTEND` on them; on the way back a `DURATION` is written as `DTEND` again when the object had one |
| `ALL_DAY` | `VALUE=DATE` dates | the provider wants UTC midnights and `EVENT_TIMEZONE` UTC |
| `RRULE` | `RRULE` | one rule; a second `RRULE` (deprecated in RFC 5545) stays object-only |
| `RDATE`, `EXDATE` | `RDATE`, `EXDATE` | the provider's comma list with an optional `TZID;` prefix |
| `EXRULE` | `EXRULE` | read only: deprecated, never written from the phone |
| `STATUS` | `STATUS` | `TENTATIVE` 0, `CONFIRMED` 1, `CANCELLED` 2 |
| `AVAILABILITY` | `TRANSP` | `OPAQUE` busy, `TRANSPARENT` free; tentative (2) has no `TRANSP` value and maps to `OPAQUE` plus `STATUS:TENTATIVE` when the object has no status of its own |
| `ACCESS_LEVEL` | `CLASS` | `PUBLIC` 3, `PRIVATE` 2, `CONFIDENTIAL` 1, absent 0 |
| `EVENT_COLOR` | `COLOR` (RFC 7986) | a CSS3 colour name on the iCalendar side, matched to the nearest one when the phone picks an arbitrary colour; not written when the calendar's own colour is meant |
| `ORGANIZER` | `ORGANIZER` | the address without `mailto:` |
| `GUESTS_CAN_MODIFY` | not mapped | |
| `HAS_ALARM`, `HAS_ATTENDEE_DATA` | derived | from the reminder and attendee rows |
| `CUSTOM_APP_URI` | not mapped | |

Everything else the object carries (`ATTACH`, `CATEGORIES`, `PRIORITY`, `URL`, `SEQUENCE` beyond what scheduling needs, `X-` properties, unknown parameters, the order and folding of lines) is object-only and survives every round trip.

## Recurrence: one occurrence edited, one deleted

iCalendar and the provider say the same two things in different shapes.

**One occurrence edited** ("this occurrence only"):
- iCalendar: a second component in the same object, same `UID`, with `RECURRENCE-ID` set to the instance's original start, carrying the instance's own properties (RFC 5545 3.8.4.4).
- Provider: an `Events` row of its own with `ORIGINAL_SYNC_ID` (the master's `_SYNC_ID`), `ORIGINAL_INSTANCE_TIME` (the original start, milliseconds UTC) and `ORIGINAL_ALL_DAY`, carrying the instance's fields.
- Mapping: one override component, one exception row, matched on the original start. A calendar app creating one inserts the row and marks it dirty; the enumerate marks its master's item dirty, the fetch adds the override component through `applyEvent`.

**One occurrence deleted:**
- iCalendar: an `EXDATE` on the master naming the instance, or an override component with `STATUS:CANCELLED`. Both are legal and both are written by real clients.
- Provider: an exception row with `STATUS` `STATUS_CANCELED`, which is what calendar apps insert, or an `EXDATE` on the master, which sync adapters may write.
- Mapping: the phone's cancelled exception row comes back as an `EXDATE` on the master, the shape every server reads; an object that cancels with an override component keeps that override, projected as a cancelled exception row, and an edit of it patches the override.

**This and following:**
- iCalendar: `RANGE=THISANDFUTURE` on a `RECURRENCE-ID`, which many servers refuse; the usual way is to end the series (`UNTIL` on the master's rule) and start a new object.
- Provider: calendar apps do the second: the master's `RRULE` gains an `UNTIL` and a new event row is inserted, with no `UID_2445`.
- Mapping: falls out of the general rules: the master's rule changes, the new row is a created event and becomes a new object with a fresh `UID`. A stored `THISANDFUTURE` override is read (ical-rs's recurrence set applies it) and projected as the series split the provider can show, then kept as it was in the object.

The agenda itself ignores both today: `calendar::expand` walks each component on its own rule, so an `EXDATE`d instance still shows and a moved one shows twice. ical-rs's `IcalRecurSet` (`of_uid` over the object's components, `with_override`, `expand_in_zone`) composes the set as RFC 5545 3.8.5 defines it, `THISANDFUTURE` included; the mirror's projection and the agenda both move onto it.

## Zones

The provider stores instants, the object civil times. A date-time maps as:

- `TZID` naming an IANA zone: as is.
- `TZID` naming a Windows zone (Graph and Exchange write them): through the CLDR `windowsZones` table to its IANA zone.
- Any other `TZID`: the object's own `VTIMEZONE`, resolved by ical-rs's `IcalTz` to the offset in force; `EVENT_TIMEZONE` then names the IANA zone whose rules match, else UTC with the instant still right.

The resolution itself is `calendar-zones-and-series`'s, shared with the agenda.
- `Z` (UTC): `EVENT_TIMEZONE` UTC.
- Floating: the device's zone, and written back floating when the phone did not change the date.

Back from the phone, a changed date is written in the zone the object had for it; a zone the phone changed is written as its IANA `TZID`, with the `VTIMEZONE` RFC 5545 3.2.19 wants in the object, built from the platform's `ZoneRules` as `calendar-zones-and-series` builds it for new entries (no time-zone database in the native library). A gap or a fold (the clock jumping) is resolved the way RFC 5545 3.3.5 says, which `IcalTzOffset::instant` implements.

## Reminders

| Reminders column | VALARM |
|---|---|
| `MINUTES` | `TRIGGER` relative to the start, negated (`-PT5M` is 5); a positive trigger (after the start) is not projected |
| `METHOD` | `ACTION:DISPLAY` and `ACTION:AUDIO` are `METHOD_ALERT`; `EMAIL` and anything else are not projected |

Not projected, and kept: alarms triggered on the end (`RELATED=END`), at an absolute time, after the start, repeating (`REPEAT`), and email ones. A phone edit replaces the projected alarms only, matched by offset, so an alarm the phone never saw is never removed by it; a reminder added on the phone becomes `BEGIN:VALARM` / `ACTION:DISPLAY` / `TRIGGER:-PT{n}M` / `DESCRIPTION` the summary.

The provider schedules and the calendar app posts: nothing on our side fires anything.

## Attendees

| Attendees column | ATTENDEE |
|---|---|
| `ATTENDEE_EMAIL` | the address without `mailto:` |
| `ATTENDEE_NAME` | `CN` |
| `ATTENDEE_TYPE` | `ROLE`: `REQ-PARTICIPANT` required, `OPT-PARTICIPANT` optional; `CUTYPE=RESOURCE` resource |
| `ATTENDEE_STATUS` | `PARTSTAT`: accepted, declined, tentative, `NEEDS-ACTION` invited |
| `ATTENDEE_RELATIONSHIP` | organizer for the `ORGANIZER`'s row, attendee otherwise |

The user's own answer (accept, decline) made in a calendar app is a `PARTSTAT` change on their own `ATTENDEE`, carried into the object. Who mails replies and invitations is the server's scheduling, never this mirror.

## Round trip and loops

The contacts' five rules, unchanged:

1. **`CALLER_IS_SYNCADAPTER` on every write.** Only calendar apps set `DIRTY`.
2. **Patch, never regenerate.** The phone's model is merged over the base's projection and applied as a patch; an untouched event comes back byte for byte, so a read is content-quiet.
3. **Diff in field space.** Times compared as instants, lists (`EXDATE`, attendees, reminders) as sets.
4. **No projected snapshot.** The base's projection is recomputed each pass.
5. **The base advances only once both sides landed.** Pushes guarded on the stamped token, projections idempotent.

And one of its own: **an override belongs to its master.** A dirty exception row dirties its master's item, an override's revision folds into its master's, and a master deleted on the phone takes its exception rows with it.
