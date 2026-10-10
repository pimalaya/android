# Android calendar to iCalendar mapping

The contract for the calendar phone mirror (cairn change `phone-calendar-mirror`): which `CalendarContract` columns map to which iCalendar properties, in both directions, what stays lossy, and the round-trip rules. The contacts twin is docs/contacts-mapping.md; its rules hold here unless this document says otherwise, where the calendar provider differs (no row version, no operation limit per batch, sub-rows updated in place).

## Architecture

```
CalendarContract rows  <-- Java (CalendarMapping) -->  phone model  <-- Rust (projectEvent / applyEvent) -->  iCalendar object
```

- **Java** reads and writes the provider and converts rows to and from the phone model, pure and JVM-tested.
- **Rust** projects the stored object onto the phone model and patches an edited model back onto it through the ical-rs CST, so everything the model does not carry survives byte for byte.
- The stored object is the record. A phone edit never regenerates it: the fetch boundary merges the phone's model over the projection of the phone base (a field is taken from the phone only when it differs from what the base projects) and applies that as a patch.

One stored item is one iCalendar object: one `UID`, a master component and the components overriding its instances. On the phone it is one master `Events` row plus one row per override, with their `Reminders`, `Attendees` and `ExtendedProperties` rows. VTODO and VJOURNAL are never projected: the provider holds events only.

## Calendar level

One Android account per Pimalaya account (type `org.pimalaya`, named by the address), syncable for `com.android.calendar` alone. One `Calendars` row per mirrored calendar.

| Calendars column | Value |
|---|---|
| `_SYNC_ID` | the collection id |
| `NAME`, `CALENDAR_DISPLAY_NAME` | the collection's name, updated only when the collection's own name changed since it was last projected (kept in `CAL_SYNC1`), so a rename made on the phone stays |
| `CALENDAR_COLOR` | the collection's colour, else the account's avatar hue, updated the same way (kept in `CAL_SYNC2`) |
| `CALENDAR_ACCESS_LEVEL` | `CAL_ACCESS_OWNER` when writable, `CAL_ACCESS_READ` otherwise; see read-only calendars |
| `OWNER_ACCOUNT` | the address |
| `IS_PRIMARY` | 1 for the collection holding pimdir's `default` role (set from each backend's own default, else the only writable calendar, else the user's choice), 0 otherwise |
| `SYNC_EVENTS`, `VISIBLE` | 1, at insert only: the user changes them |
| `CALENDAR_TIME_ZONE` | the collection's `calendar-timezone` when `java.time` knows it, else none |
| `ALLOWED_REMINDERS` | `1` (alert) |
| `MAX_REMINDERS` | 5, the provider's default, written explicitly |
| `ALLOWED_AVAILABILITY` | `0,1` (busy, free) |
| `ALLOWED_ATTENDEE_TYPES` | `0,1,2,3` (none, required, optional, resource) |
| `CAN_ORGANIZER_RESPOND`, `CAN_MODIFY_TIME_ZONE` | 1 |
| `CAN_PARTIALLY_UPDATE` | 0: at 1 the provider keeps a hidden `LAST_SYNCED` copy of every row an app edits, which sync-adapter queries see; at 0 there is none, so no query filters on `LAST_SYNCED` |
| `CAL_SYNC1`, `CAL_SYNC2` | the name and colour last projected |
| `CAL_SYNC3` | the count of the calendar's objects that show nothing on the phone (tasks, journal entries, writes the provider refused), for the quiet path's member count |

A calendar the user unsyncs in a calendar app (`SYNC_EVENTS` 0) stays mirrored, but the provider expands no instances and fires no reminders for it; which calendars reach the phone is chosen in Pimalaya.

A `Calendars` row is rewritten only when one of its columns changes: every write notifies the calendar apps. A row inserted, or deleted (the calendar switched off, its account removed), makes the store forget what the phone held of that calendar, its phone bindings dropped as superseded and never as removals: the provider deletes a calendar's events with its row, so a calendar shown again is projected whole.

**Colours.** The account's `Colors` table is seeded, as sync adapter and before any event is written, with the CSS3 named colours as `TYPE_EVENT` rows keyed by the name. The provider refuses an `EVENT_COLOR_KEY` absent from that table, and calendar apps offer event colours only from it.

**Read-only calendars.** The provider does not enforce `CALENDAR_ACCESS_LEVEL`: any app with the permission can write a `CAL_ACCESS_READ` calendar. A phone change there is never ingested: the base projection is pushed back, which re-projects an edited row, undeletes (`DELETED` 0) and re-projects a deleted one, and removes a created one.

## Event level

| Events column | iCalendar | Notes |
|---|---|---|
| `_SYNC_ID` | the item's handle | masters only, stamped on masters a calendar app created at the first phone pass after they appear (`<uuid>.ics`); override rows keep it NULL; the standalone row of an override in an object without a master carries the item's handle, U+001F and the override's `RECURRENCE-ID` |
| `UID_2445` | `UID` | override rows carry the master's; see created events |
| `SYNC_DATA1` | revision token | ours, stamped at convergence; a sync-adapter-only column, so no calendar app can forge it |
| `SYNC_DATA2` | | the master's count of exception rows at convergence; see one occurrence reverted |
| `SYNC_DATA3` | | the row's own `_ID` at convergence; see created events |
| `SYNC_DATA4` | | what the projection depended on besides the object: `zone=<id>` for an object with a floating time, `window=<YYYYMM>` for a series listed instance by instance; a clean row projected for another device zone or month is projected again |
| `DIRTY`, `DELETED` | | set by calendar apps; our writes carry 0 for both |
| `TITLE` | `SUMMARY` | |
| `DESCRIPTION` | `DESCRIPTION` | |
| `EVENT_LOCATION` | `LOCATION` | |
| `DTSTART` + `EVENT_TIMEZONE` | `DTSTART` with its `TZID` | milliseconds UTC plus a zone id the device knows, never NULL (the provider reads NULL as UTC); see zones |
| `DTEND` + `EVENT_END_TIMEZONE` | `DTEND` | single events and override rows; `EVENT_END_TIMEZONE` from `DTEND`'s own zone, read as `EVENT_TIMEZONE` when absent; ignored on recurring rows when read, since the provider writes 0 there after a tzdata update |
| `DURATION` | `DURATION`, or `DTEND - DTSTART` | masters with `RRULE` or `RDATE` only: the provider drops `DTEND` on a recurring row and leaves it endless without `DURATION`. Written in RFC 5545 form, always `P<n>D` on all-day rows (a `PT..S` form crashes the provider's all-day fix-up); read in RFC form and in Etar's `P<n>S`. On the way back a `DURATION` is written as `DTEND` again when the object had one |
| `ALL_DAY` | `VALUE=DATE` dates | UTC midnights and `EVENT_TIMEZONE` UTC; on read `EVENT_TIMEZONE` is ignored (some vendors write junk there), the times are read as UTC dates, and an end not after the start is one day |
| `ORIGINAL_ID`, `ORIGINAL_SYNC_ID`, `ORIGINAL_INSTANCE_TIME`, `ORIGINAL_ALL_DAY` | `RECURRENCE-ID` | override rows; see recurrence |
| `RRULE` | `RRULE` | see recurrence |
| `RDATE`, `EXDATE` | `RDATE`, `EXDATE` | see recurrence |
| `EXRULE` | `EXRULE` | projected, never taken from the phone (deprecated in RFC 5545) |
| `STATUS` | `STATUS` | `TENTATIVE` 0, `CONFIRMED` 1, `CANCELLED` 2. Never written NULL (updating a value to NULL crashes the provider): an object without `STATUS` projects `CONFIRMED`, an unknown value `TENTATIVE`. A phone `CONFIRMED` against a base without `STATUS` is no edit; `TENTATIVE` on an existing row is one. On a new override row it is no edit, the override taking the master's status: the provider's exception path writes `TENTATIVE` when the caller gives no status, which cannot be told from a choice |
| `AVAILABILITY` | `TRANSP` | `OPAQUE` busy (0), `TRANSPARENT` free (1); tentative (2) is not offered, and reads as `OPAQUE` from an app that writes it anyway |
| `ACCESS_LEVEL` | `CLASS` | `PUBLIC` 3, `PRIVATE` 2, `CONFIDENTIAL` 1, absent 0; an unknown value projects `PRIVATE` (RFC 5545 3.8.1.3) |
| `EVENT_COLOR_KEY` | `COLOR` (RFC 7986) | the CSS3 name, set from `COLOR` by exact name; empty or NULL is no `COLOR`, the calendar's colour. `EVENT_COLOR` is the provider's, filled from the key, never written nor read |
| `ORGANIZER` | `ORGANIZER` | the address without `mailto:` (another URI gives its `EMAIL` parameter); an override row carries the master's. An object without one leaves it to the provider, which fills the owner on insert, so the phone's `ORGANIZER` reaches the object only when the event has attendees |
| `IS_ORGANIZER` | | written on every row: 1 when the object has no `ORGANIZER` or names one of the account's addresses, compared case-insensitively. The provider's fallback is a case-sensitive `ORGANIZER = OWNER_ACCOUNT`, and Etar lets the user edit only what they organize |
| `HAS_ALARM` | | the provider's, from the `Reminders` rows; never written |
| `HAS_ATTENDEE_DATA` | | written 1: the provider does not derive it, and apps hide the guest list at 0 |
| `GUESTS_CAN_MODIFY` | not mapped | left 0, so Etar edits only the events the user organizes and answers the others' |
| `CUSTOM_APP_URI` | not mapped | |

| ExtendedProperties row | iCalendar | Notes |
|---|---|---|
| `vnd.android.cursor.item/vnd.ical4android.url` | `URL` | where Etar edits it (rewritten on each save, as sync adapter, beside its update of the event row) |
| any other | not mapped | never touched: rows we did not write belong to the apps that wrote them |

Everything else the object carries (`ATTACH`, `CATEGORIES`, `PRIORITY`, `X-` properties, unknown parameters, the order and folding of lines) is object-only and survives every round trip. An applied phone edit refreshes `DTSTAMP` (and `LAST-MODIFIED` when present) on each component it changes; `SEQUENCE` is bumped, once per applied edit on each component it changes, only when the user organizes an event with attendees (RFC 5546); the same rule (`organized` in rust/src/calendar.rs) decides whether a push asks Google and JMAP to notify the attendees. An object created from the phone gets `DTEND`, never `DURATION`: iCloud refuses events without `DTEND`.

An object with neither `DTEND` nor `DURATION` projects an end equal to `DTSTART` for a date-time start and `DTSTART` plus one day for a date (RFC 5545 3.6.1); a phone end equal to that is no edit and the object stays without one.

**Created events.** A master without `_SYNC_ID` was created in a calendar app: it is stamped a fresh handle and becomes a new object, with the `UID_2445` the app wrote, else a fresh `UID`. The provider's own "this and following" path clones `_SYNC_ID`, `SYNC_DATA*` and `UID_2445` into the new row, so a master whose `SYNC_DATA3` is set and differs from its `_ID`, or a second master holding the same `_SYNC_ID` (the row with the lower `_ID` keeps it), is a clone: a created event, stamped a fresh handle and given a fresh `UID`, before listing.

## Recurrence

### Rules and dates

- **`RRULE`**: the object's rules, several joined by a newline in the one cell (the provider splits it). A phone `UNTIL` is coerced to `DTSTART`'s value type, in UTC when `DTSTART` is zoned (RFC 5545 3.3.10): Etar writes a date-time `UNTIL` on all-day series.
- **`RDATE`, `EXDATE`**: the provider's cell, `[TZID;]v1,v2`, several such lines joined by a newline accepted on read. Every value is written with a `TZID` prefix or in UTC (`Z`): the provider reads a bare value as UTC. This mirror writes them all in UTC. Values are aligned to `DTSTART`'s type: on an all-day series, UTC midnights; on a timed one, a date becomes the date-time at `DTSTART`'s time in its zone (the provider removes nothing with a date `EXDATE` there). The `RDATE` cell includes `DTSTART`, since the provider otherwise drops the first instance; the projection includes it too, so the diff never sees it as an added date. An all-day `EXDATE` coming back is written `VALUE=DATE`: strict servers refuse a bare date.
- Rules, `RDATE` and `EXDATE` are compared parsed, as sets, never as text: Fossify regenerates the `RRULE` text on every edit.
- Override rows never carry recurrence columns (the provider turns an override row with a rule into a series of its own), and those found on one are ignored on read: Etar copies the master's `EXDATE` into it.
- Overrides are projected only when the master has an `RRULE` or an `RDATE`.

### Series the provider cannot show

Some recurrences the provider refuses or expands wrong:

- `RSCALE` or `SKIP` (RFC 7529): the insert is refused.
- `BYSETPOS` other than `FREQ=MONTHLY` with a plain `BYDAY`, and `BYWEEKNO`: misexpanded (`BYSETPOS` ignored, so every matching day shows).
- An infinite `RRULE` together with an `RDATE`: the insert fails.
- An `RDATE` of `VALUE=PERIOD`: not parsed.
- A rule whose window lies more than 2000 periods of its `FREQ` after `DTSTART`: the provider's expansion gives up after 2000 periods, so a daily series started over five years ago shows nothing. The periods are counted to the window's start, or to the rule's `UNTIL` when sooner, and at most its `COUNT`.
- A `RANGE=THISANDFUTURE` override: the provider has no such override (see this and following).

Such a series is projected as an `RDATE` list of its instances, from the composed recurrence set (`IcalRecurSet`) over a window of one year back and two years ahead, with no `RRULE`, `EXRULE` or `EXDATE`, and re-projected as the window moves, which it does by the month (`SYNC_DATA4`). The list holds at most 1000 instances: past that, the window is cut from its far end, so the instances nearest to now are kept. The row's `DTSTART` is the list's first instance, so the provider shows no instance the list does not hold. An instance an override replaces is listed at its identity, which the override's row hides; any other at the start the recurrence set gives it, a `THISANDFUTURE` shift included, and an occurrence the phone edits there is mapped back to the instance it is. Its dates are never diffed: a phone edit of the series' start, end or recurrence is reverted (the base projection pushed back), and its other fields are taken. Each listed date is a real instance, so an occurrence edited or cancelled on the phone maps as below. This is a view limitation: the object keeps the real rule, and the phone shows only the window.

### One occurrence edited ("this occurrence only")

- iCalendar: a second component in the same object, same `UID`, with `RECURRENCE-ID` set to the instance's original start, carrying the instance's own properties (RFC 5545 3.8.4.4).
- Provider: an `Events` row of its own with `ORIGINAL_SYNC_ID` (the master's `_SYNC_ID`), `ORIGINAL_INSTANCE_TIME` and `ORIGINAL_ALL_DAY` (the master's all-day flag), carrying the instance's fields, `DTEND` and never `DURATION`. Inserted on `Events.CONTENT_URI`, never `CONTENT_EXCEPTION_URI`, which drops sync columns and refuses masters with `RDATE` alone. The provider pairs an override with its master by `ORIGINAL_SYNC_ID` alone, never `ORIGINAL_ID`, and only when `ORIGINAL_INSTANCE_TIME` equals an instance it generates, to the millisecond; otherwise the instance shows beside the override. So `ORIGINAL_INSTANCE_TIME` is the instance's original start in UTC milliseconds, whole seconds (Android before 12 matches at second resolution), the `RECURRENCE-ID` coerced to `DTSTART`'s value type and expanded in the master's zone (UTC midnight for all-day).
- An override row without `ORIGINAL_SYNC_ID` (an app linking by `ORIGINAL_ID` alone, as Fossify does) gets it, resolved through `ORIGINAL_ID`. A row with `ORIGINAL_INSTANCE_TIME` and neither link has no reachable master and is not mirrored.
- Mapping: one override component, one exception row, matched on the original start. A calendar app creating one inserts the row and marks it dirty; the enumerate marks its master's item dirty, the fetch adds the override component through `applyEvent`. The new row is diffed against the master's projection of that instance, not against nothing: apps copy the master's fields, reminders and attendees into it, and the provider's exception path defaults fields such as `STATUS` to tentative; only the fields that differ become the override's properties. A new override's `RECURRENCE-ID` is written in the master's zone, `VALUE` matching `DTSTART`.

### One occurrence deleted

- iCalendar: an `EXDATE` on the master naming the instance, or an override component with `STATUS:CANCELLED`. Both are legal and both are written by real clients.
- Provider: an exception row with `STATUS` `STATUS_CANCELED`, which is what calendar apps insert (Etar cancels an existing override by updating its `STATUS`), or an `EXDATE` on the master, which sync adapters may write.
- Mapping: the phone's cancelled exception row comes back as an `EXDATE` on the master, the shape every server reads, aligned to `DTSTART` (`VALUE=DATE` for all-day, `DTSTART`'s `TZID` when zoned, `Z` in UTC); an object that cancels with an override component keeps that override, projected as a cancelled exception row, and an edit of it patches the override. A cancelled exception row naming an instance the object already excludes (the `EXDATE` an earlier cancellation wrote, which the phone's row then stands for, or an instance a listed series no longer lists) is no edit, and an `EXDATE` is never written twice.

### One occurrence reverted

Override rows have no `_SYNC_ID`, so an app deleting one hard-deletes it without dirtying the master. `SYNC_DATA2` holds the master's exception count at convergence, and a count that dropped dirties the item. An override row deleted (`DELETED` 1, or vanished) means that override is removed: the override component goes and the instance reverts to the series.

### This and following

- iCalendar: `RANGE=THISANDFUTURE` on a `RECURRENCE-ID`, which many servers refuse; the usual way is to end the series (`UNTIL` on the master's rule) and start a new object.
- Provider: calendar apps do the second: the master's `RRULE` gains an `UNTIL` and a new event row is inserted. Etar's new row has no `UID_2445` and no `_SYNC_ID`; the provider's own split path clones both into it (see created events).
- Mapping: falls out of the general rules: the master's rule changes, the new row is a created event and becomes a new object with a fresh `UID`. A stored `THISANDFUTURE` override is read (ical-rs's recurrence set applies it) and the series projected as an instance list (series the provider cannot show), then kept as it was in the object. The contract first had it projected as the series split the provider can show; that takes two master rows for one object, each with its own overrides, where the list already carries the shifted instances.

Some app edits are a delete plus an insert (Etar making a series non-recurring, any app moving an event to another calendar): the stored object is deleted and a new one created. That loss of identity is accepted.

### A master deleted

An app deleting a stamped master soft-deletes it (`DELETED` 1), hard-deletes its unstamped override rows and drops its reminders and extended properties at once; the item is deleted. Our purge of a deleted master is a sync-adapter delete, which removes that row alone, so it deletes the master's exception rows explicitly. The store keeps the item as a staged removal for the server, whose next pass carries it out; the same holds the other way, a server's deletion reaching the phone, the last source retiring the item.

A master whose every instance is cancelled on the phone stays: the object keeps its series with every instance excluded, and only a delete deletes it.

### An invitation to one instance

An object with overrides but no master (the user invited to one instance): each override is projected as a standalone event row, its handle the item handle plus its `RECURRENCE-ID` (event level, `_SYNC_ID`), and an edit of it patches that override; one of its rows deleted removes that override, all of them the item.

The agenda itself ignores all this today: `calendar::expand` walks each component on its own rule, so an `EXDATE`d instance still shows and a moved one shows twice. ical-rs's `IcalRecurSet` (`of_uid` over the object's components, `with_override`, `expand_in_zone`) composes the set as RFC 5545 3.8.5 defines it, `THISANDFUTURE` included; the mirror's projection and the agenda both move onto it.

## Zones

The provider stores instants, the object civil times. A date-time maps as:

- `TZID` naming an IANA zone: as is, matched case-insensitively and trimmed (Outlook writes a trailing space), a name prefixed by a path (`/freeassociation.sourceforge.net/Tzfile/Europe/Vienna`) read as the IANA name it ends with. A name the device's tzdata does not know (`Europe/Kyiv` on an older device) is resolved like any other `TZID`.
- `TZID` naming a Windows zone (Graph and Exchange write them): through the CLDR `windowsZones` table to its IANA zone.
- Any other `TZID`: the object's own `VTIMEZONE`, resolved by ical-rs's `IcalTz` to the offset in force; `EVENT_TIMEZONE` then names the IANA zone whose rules give identical offsets over the event's span, else the device's zone, never UTC: the provider expands rules in `EVENT_TIMEZONE`, so UTC would move a Friday 23:00 series to Saturdays. The instant stays right either way.
- `Z` (UTC): `EVENT_TIMEZONE` UTC.
- Floating: the device's zone, re-projected when the device's zone changes (a sync-adapter write, never an edit), and written back floating when the phone did not change the date. A floating time the phone moved stays floating, read on the row's wall clock, while the row's zone is the device's or the one it was projected in; moved into another zone, it is written in that zone.
- All-day: UTC, as the event level says.

The resolution itself is `calendar-zones-and-series`'s, shared with the agenda.

Back from the phone, a changed date is written in the zone the object had for it (in a zone only the object defines, from its instant, the object's `VTIMEZONE` giving the offset at the new time); a zone the phone changed is written as its IANA `TZID`, with the `VTIMEZONE` RFC 5545 3.2.19 wants in the object, built from the platform's `ZoneRules` as `calendar-zones-and-series` builds it for new entries (no time-zone database in the native library), and placed before the components that use it (Mailfence refuses an object otherwise). A gap or a fold (the clock jumping) is resolved the way RFC 5545 3.3.5 says, which `IcalTzOffset::instant` implements.

## Reminders

| Reminders column | VALARM |
|---|---|
| `MINUTES` | `TRIGGER` relative to the start, negated (`-PT5M` is 5, seconds dropped); an alarm after the start is negative minutes, which the provider fires |
| `METHOD` | `ACTION:DISPLAY` and `ACTION:AUDIO` are `METHOD_ALERT`; `EMAIL` and any other action are not projected |

Converted to start-relative minutes: alarms triggered on the end (`RELATED=END`), through the event's duration; alarms at an absolute time, on non-recurring events only; repeating alarms (`REPEAT`), by their first trigger. Each converted reminder round-trips: unchanged on the phone, it is no edit and its VALARM stays byte for byte; removed, that VALARM is removed; changed, it is rewritten as a start-relative `TRIGGER`.

A phone edit replaces the projected alarms only, matched by offset (apps delete and reinsert every reminder on save, so the offset is the only stable identity), so an alarm the phone never saw is never removed by it. A reminder added on the phone becomes `BEGIN:VALARM` / `ACTION:DISPLAY` / `TRIGGER:-PT{n}M` / `DESCRIPTION` the summary; `METHOD_ALERT` and `METHOD_DEFAULT` rows are both `DISPLAY`. `METHOD_EMAIL` rows (Fossify writes them) stay phone-only: never in the object, never deleted by our push.

The provider schedules and a calendar app posts; nothing on our side fires anything. The provider fires `METHOD_ALERT` reminders only, and only on `VISIBLE` calendars (and `SYNC_EVENTS` ones, which alone have instances), by broadcasting `EVENT_REMINDER`, and a calendar app must post the notification. Etar and AOSP-derived apps do; Google Calendar only for accounts the user enabled in it; Fossify does not, alarming only for calendars imported into it. With no posting app there is no notification; with two, it shows twice.

## Attendees

| Attendees column | ATTENDEE |
|---|---|
| `ATTENDEE_EMAIL` | the address without `mailto:` (another URI gives its `EMAIL` parameter, and is not projected without one), matched case-insensitively |
| `ATTENDEE_NAME` | `CN` |
| `ATTENDEE_TYPE` | `ROLE`: none (0) is no `ROLE` parameter, and `NON-PARTICIPANT` projects 0; `REQ-PARTICIPANT` and `CHAIR` required (1), `OPT-PARTICIPANT` optional (2); resource (3) is `CUTYPE=RESOURCE` or `ROOM` |
| `ATTENDEE_STATUS` | `PARTSTAT`: accepted, declined, tentative; `NEEDS-ACTION` is invited (3), and none (0, Etar's default for added attendees) reads as `NEEDS-ACTION`; `DELEGATED` projects 0 |
| `ATTENDEE_RELATIONSHIP` | organizer for the `ORGANIZER`'s row, attendee otherwise |

The user's own row is written with `ATTENDEE_EMAIL` spelled as `OWNER_ACCOUNT`: the provider derives `SELF_ATTENDEE_STATUS`, which cannot be written on `Events`, from the attendee whose address equals it case-sensitively. The user's own answer (accept, decline) made in a calendar app is a `PARTSTAT` change on their own `ATTENDEE`, carried into the object. Who mails replies and invitations is the server's scheduling, never this mirror.

Etar adds an organizer row beside new attendees: it is an `ATTENDEE` for the organizer, legal in RFC 5545. An attendee list holding the organizer alone, with no other attendee, adds nothing.

## Change detection and writes

- **Masters** are the rows with `ORIGINAL_ID`, `ORIGINAL_SYNC_ID` and `ORIGINAL_INSTANCE_TIME` all NULL; any other row is an override.
- **The change signal is `DIRTY`**, which the provider sets on every app write to a row or its sub-rows; `Events` has no row version (contacts' `VERSION` has no twin here). Selections test `DIRTY = 1`, never `NOT DIRTY`: `DIRTY` can be NULL.
- **Enumerate:** the masters, each handle its `_SYNC_ID`, its revision `SYNC_DATA1` or a dirty sentinel when the master or one of its overrides is dirty or deleted, or its exception count dropped below `SYNC_DATA2`. The sentinel is `dirty:` and a digest of the object's rows, so a further edit moves it and a second read of the same rows does not. Deleted masters are purged and absent; created masters and clones are stamped before listing, and the rows of an object the store no longer holds are purged while clean.
- **Read stamp:** each read stamps the object's rows converged behind assert queries on every column, reminder and attendee it read and on the object's row count, deleted exception rows purged in the same batch.
- **Guarded push:** every update selects `_ID = ? AND (DIRTY IS NULL OR DIRTY = 0)` with an expected count of 1, so a row a calendar app dirtied since the read aborts the batch and stays dirty for the next pass. The convergence stamp (`DIRTY` 0, `SYNC_DATA1`, `SYNC_DATA2`, `SYNC_DATA3`) goes in the same `applyBatch`, behind assert queries on the columns read; the provider applies a batch as one SQLite transaction.
- **In place:** rows are updated, never deleted and reinserted; sub-rows (reminders, attendees, overrides) are diffed and updated, inserted or deleted one by one, so vendor apps' private state and row ids survive.
- **Batches:** one object (master, overrides, sub-rows) per `applyBatch`, with no operation limit (the 500 of contacts is that provider's); a batch is split only on `TransactionTooLargeException`, the guards kept on every part: the object's new event rows are inserted first, then the rest goes in parts of 100 writes, each behind an assertion that its rows are still clean. The one case where an object lands in more than one transaction.
- **URIs:** every write carries `CALLER_IS_SYNCADAPTER`, `ACCOUNT_NAME` and `ACCOUNT_TYPE`; the provider refuses a sync-adapter write without the account.
- **Refused projections:** a write the provider refuses for one item, or an object the native side cannot read, is caught there: the item stays in the store and off the phone, is logged, and is retried only when it changes. It counts, like a task, among the objects that show nothing (`CAL_SYNC3`).
- **Accounts:** removing the Android account makes the provider delete its calendars with every unsynced phone edit in them, so a phone pass runs before any removal. Android runs the upload syncs that calendar-app edits request only with the device's auto-sync on, which is why the passes after writes and on return are needed.

## Round trip and loops

The contacts' five rules, adapted:

1. **`CALLER_IS_SYNCADAPTER` on every write.** Only calendar apps set `DIRTY`.
2. **Patch, never regenerate.** The phone's model is merged over the base's projection and applied as a patch; an untouched event comes back byte for byte, so a read is content-quiet. Rows are updated in place on the way out too.
3. **Diff in field space.** Times compared as civil times in the row's zone, the zone with them (dates for all-day), not instants: tzdata updates rewrite `DTSTART` and `DTEND` without `DIRTY`, and a `VTIMEZONE` resolution or a traveling device would otherwise make phantom edits. Two exceptions: a floating base's time is compared on the wall clock alone, the row's zone being the device's; and a time in a zone only the object defines by its instant, the zone the row names for it being the device's choice (zones), which moves with the device while the instant does not. A rule's `UNTIL` is coerced on both sides before comparing, a date `UNTIL` against a zoned start bounding its whole day (23:59:59 in the start's zone, in UTC). `DTEND` on recurring rows is ignored. Rules, `RDATE`, `EXDATE` and attendees compared parsed, as sets, never as text; reminders as sets of offsets; durations as lengths. Both sides go through the same mapping to rows, so a value the provider cannot hold (`DELEGATED`, `NON-PARTICIPANT`, an unknown `STATUS`) is no edit while the phone leaves it.
4. **No projected snapshot.** The base's projection is recomputed each pass.
5. **The base advances only once both sides landed.** Pushes guarded on the row not being dirty, the convergence stamp in the same transaction, projections idempotent.

And one of its own: **an override belongs to its master.** A dirty, deleted or vanished exception row dirties its master's item, an override's revision folds into its master's, and a master deleted on the phone takes its exception rows with it, deleted explicitly by our purge.

## Provider quirks

| The provider | So the contract |
|---|---|
| crashes on an update of `STATUS` to NULL | never writes it NULL |
| drops `DTEND` on a recurring row, and `DURATION` on an override or single row | writes `DURATION` on masters with a rule, `DTEND` elsewhere |
| crashes on a `PT..S` duration on an all-day row | writes `P<n>D` there |
| rewrites `DTSTART` and `DTEND` on a tzdata update, without `DIRTY`, and `DTEND` 0 on recurring rows | diffs civil times, ignores `DTEND` on recurring rows |
| drops the first instance of a series with an `RDATE` | puts `DTSTART` in the `RDATE` cell |
| reads a bare `RDATE` or `EXDATE` value as UTC, and removes nothing with a date `EXDATE` on a timed series | writes a `TZID` prefix or `Z`, aligned to `DTSTART` |
| refuses `RSCALE`, `SKIP` and an infinite rule with `RDATE`, misexpands `BYSETPOS` and `BYWEEKNO`, stops after 2000 periods | projects those series as an instance list |
| pairs an override with its master by `ORIGINAL_SYNC_ID` alone and the exact instance time, at second resolution before Android 12 | stamps masters promptly, writes `ORIGINAL_SYNC_ID` and whole seconds |
| defaults `STATUS` to tentative on its exception path | diffs a new override against the master's projection |
| clones `_SYNC_ID`, `SYNC_DATA*` and `UID_2445` in its own split | detects clones by `SYNC_DATA3` |
| hard-deletes an unstamped override without dirtying its master | counts exception rows in `SYNC_DATA2` |
| does not cascade a sync-adapter delete to exceptions | deletes them explicitly |
| refuses an `EVENT_COLOR_KEY` absent from the account's `Colors` | seeds the CSS3 palette |
| fills `ORGANIZER` with the owner on insert, and compares it case-sensitively | takes the phone's `ORGANIZER` only with attendees, writes `IS_ORGANIZER` |
| does not enforce `CALENDAR_ACCESS_LEVEL` | reverts phone changes on read-only calendars |
| purges an account's calendars when the account goes | runs a phone pass before removal |

## Limitations

- **Reminders depend on the calendar app.** Without an app posting `EVENT_REMINDER` (Fossify alone, or Google Calendar with the account not enabled) nothing notifies; with two such apps, each reminder shows twice. Pimalaya fires no alarm of its own.
- **Event colours.** Some calendar apps mishandle them (AOSP, Google and Samsung Calendar may crash or not save an event with a colour; Google Calendar offers none on non-Google accounts).
- **Series the provider cannot show** appear as a window of instances, from one year back to two ahead, with their dates read-only on the phone.
- **Fossify's saves** rewrite the rule from its own model (a rule edited there loses parts such as `BYSETPOS` or `BYMONTHDAY`), every attendee as required, and at most three reminders; the mirror takes each as the user's edit.
- **Delete plus insert edits** (a series made non-recurring in Etar, an event moved to another calendar) lose the object's identity.
- **Android before 12** expands no recurrence past 2037.
- **A cancelled single event** (not an override) may still show: the provider hides it on update but expands it again on a full regeneration.
