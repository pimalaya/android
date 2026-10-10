---
cairn: change
id: phone-calendar-mirror
status: active
created: 2026-10-09
---

# Phone calendars: the store's events in the phone's Calendar app, both ways

## Why

The app never touches `CalendarContract`. A user who sets up an address gets their contacts in the phone's Contacts app and nothing in its Calendar app: no events in the calendar they already use, no widget, no other app seeing them, and no reminders at all, since the app has no alarms of its own. The release promise ("put your address in, get your mail, contacts and calendars, in step with the phone") is broken for the third of them.

The calendar takes the contacts' design whole. iCalendar is the truth, the store keeps it; `CalendarContract` is a view, editable from any calendar app, so it is a second source of each calendar (`phone`, beside `server`), reconciled by the same engine with the same three-way merge. The rule that makes an editable view safe holds here as there: a round trip through the phone loses nothing the phone cannot represent, because a phone edit is a field-space diff applied as a patch onto the stored object, never an object rebuilt from rows.

Reminders come with it, and are the reason not to build our own: a VALARM projected as a `Reminders` row is scheduled by the calendar provider itself (`CalendarAlarmManager`, exact alarms allowed while idle, recurring instances expanded, rescheduled on boot and zone change) and posted by a calendar app that handles `ACTION_EVENT_REMINDER`: Etar and AOSP-derived apps do, Google Calendar only for accounts the user enabled in it, Fossify does not (it alarms only for calendars imported into it). Our own `AlarmManager` reminders would duplicate every one of them on a phone with such an app. The cost is a stated limitation: with no posting app nothing notifies, with two each reminder shows twice (docs/calendar-mapping.md, limitations).

## Depends on

`calendar-zones-and-series` and `calendar-conflict-form`, which land first: zones resolved to instants, edits keeping them, the recurrence set composed, single occurrences written as overrides and `EXDATE`s, series split; and conflicts settled in a form, never dropped. The mirror reuses each piece.

## What is missing

Nothing of this exists. Against the contacts mirror, which is the model:

| Piece | Contacts | Calendars |
|---|---|---|
| Rows to model and back, pure, JVM-tested | `Mapping` | missing (`CalendarMapping`) |
| Object to model, model patched onto object | `projectCard` / `applyCard` | half: `readEvent` reads the first component, `writeEvent` patches eleven fields of it |
| The provider adapter (enumerate, fetch, push) | `PhoneRemote` | missing (`CalendarRemote`) |
| The engine routing phone collections to it | `OfflineEngine` | missing in `CalendarEngine` |
| The two-source storage | `PimdirStorage` (`phone:` collections, `phone` binding) | already generic, reused as is |
| Android accounts and the sync adapter | `Accounts`, `SyncService`, `xml/syncadapter` | missing for `com.android.calendar` |
| Permissions | `READ/WRITE_CONTACTS` | missing (`READ/WRITE_CALENDAR`) |
| Conflicts | `resolvePhoneConflict` over a vCard three-way merge | missing on both sources: a calendar has no conflict handling at all today |

ical-rs 0.5.3, which the app already builds against, has every primitive the iCalendar half needs and the app uses none of them: the recurrence set (`IcalRecurSet`: `RRULE`, `RDATE`, `EXDATE`, `EXRULE` and the overriding components, `THISANDFUTURE` included), zone resolution from the object's own `VTIMEZONE` (`IcalTz`), `VTIMEZONE` synthesis from the IANA database (the `tzdb` feature, which this app keeps off: Android ships the database), and the three-way merge (`IcalMerge`). What it does not have is the Windows zone names Graph and Exchange write.

And five gaps in the app's iCalendar half that the mirror cannot work around:

1. **Time zones.** `calendar.rs` works in civil time on purpose (an agenda never needs an instant), but the provider stores instants (`DTSTART` in UTC milliseconds plus `EVENT_TIMEZONE`). Worse, `write`'s `push_date` drops the `TZID` of the start it replaces: an event at 09:00 Europe/Paris edited in the app today comes back floating. That is a bug now, before any mirror.
2. **Recurrence.** `read` exposes the rule as text and `write` cannot change it; the phone edits `RRULE`, `RDATE` and `EXDATE`.
3. **Overrides.** A stored object is a master plus the components overriding its instances (`RECURRENCE-ID`); `read` and `write` see the master alone. The provider stores each override as a row of its own (`ORIGINAL_SYNC_ID`, `ORIGINAL_INSTANCE_TIME`), a cancelled instance as a row with `STATUS_CANCELED`, and a calendar app editing "this occurrence only" inserts such a row.
4. **Alarms.** `write` keeps VALARMs untouched, which is right for the form and not enough for the phone, whose reminders are edited.
5. **The agenda's expansion.** `calendar::expand` walks each component on its own rule (`IcalRecurExpand`): an `EXDATE`d occurrence still shows, and a moved one shows twice, at both its times. A bug now, and the projection needs the composed set anyway.

## What

### Phase 0: the contract

`docs/calendar-mapping.md` (drafted with this change) is the contract, as `docs/contacts-mapping.md` is for contacts: which `CalendarContract` column maps to which iCalendar property, both directions, what is lossy, and the round-trip rules. Agreed before code.

Before it is agreed, research agents studied how existing apps solve the same mapping and brought back what the draft missed or got wrong: DAVx5 and its synctools / ical4android (the reference two-way CalDAV mirror: exceptions, `DURATION` vs `DTEND`, all-day, zones, `UNKNOWN_PROPERTY` rows, dirty exceptions), Etar and AOSP's `CalendarProvider` (what the provider does with what we write, how alarms are scheduled), ICSx⁵ (read-only subscriptions), and Fossify Calendar. The contract was revised from their findings (2026-10-09), with the provider's quirks and the mirror's limitations stated, and is then agreed.

### Phase 1: the iCalendar half (Rust)

- **`projectEvent(ical)`**: the whole object as the phone model, the master and each override (keyed by `RECURRENCE-ID`), each with its fields, its zone as `calendar-zones-and-series` resolves it (the instant, and the IANA id the provider's `EVENT_TIMEZONE` names), its rule set, its alarms (end-related, absolute and repeating ones converted to start-relative minutes) and its attendees. A series the provider cannot show (`RSCALE`, `SKIP`, the `BYSETPOS` and `BYWEEKNO` it misexpands, an infinite rule with `RDATE`, `PERIOD` dates, more than 2000 periods to the window) is projected as an `RDATE` list of its `IcalRecurSet` instances over one year back and two ahead; an object without a master as one standalone event per override.
- **`applyEvent(ical, model)`**: the model patched onto the object through the CST, the managed properties alone: the eleven of today plus the rule set, `TRANSP`, `CLASS`, `COLOR`, the alarms the phone can carry, the attendees' standing; overrides added, changed and cancelled by `RECURRENCE-ID`. Everything else rides along byte for byte, as with `applyCard`.
- **Three-way merge** for the phone's divergences: `IcalMerge`, which takes a field only one side touched from that side and reports a field both touched as a conflict, the same shape as `Cards.mergeConflictForm`. It also gives the server source its first conflict handling.

### Phase 2: the device half (Java)

- **Accounts:** one Android account per Pimalaya account (`org.pimalaya`, named by the address), syncable for `com.android.calendar` alone. Calendar apps group calendars by account, so each address shows as its own group, never mixed; it is what DAVx5 does (one account per CalDAV account, while contacts take one account per address book, as ours already do, since `ContactsContract` has no collection but the account). Which accounts and calendars reach the phone is chosen in Pimalaya: the switch in both setups, then per calendar in the settings (agreed 2026-10-09). Then: the account's `Colors` table seeded with the CSS3 named colours, and one `Calendars` row per mirrored calendar, its columns as the contract's calendar level sets them (`_SYNC_ID` the collection id, access level `OWNER` or `READ` from the collection's writability, `SYNC_EVENTS` and `VISIBLE` at insert only, name and colour updated only when the collection's own changed, so the user's choices on the phone stay). Reconciled on the switch, at startup and on account removal, like the books' accounts; a phone pass runs before a removal, since the provider then deletes the calendars with their unsynced edits.
- **`CalendarSyncService`** with `xml/syncadapter_calendar` (`contentAuthority="com.android.calendar"`, `supportsUploading`), running the phone pass alone, offline, on Android's upload syncs.
- **`CalendarMapping`** (pure, JVM-tested): the phone model to `Events`, `Reminders` and `Attendees` rows and back, and the field-space `merge` (take a field from the phone only when it differs from what the base itself projects), as `Mapping` does.
- **`CalendarRemote`**: the provider adapter.
  - *Enumerate:* the calendar's master rows (`ORIGINAL_ID`, `ORIGINAL_SYNC_ID` and `ORIGINAL_INSTANCE_TIME` all null), each handle its `_SYNC_ID`, its revision the token we stamped (`SYNC_DATA1`) or a dirty sentinel when the master or any of its overrides is `DIRTY` or deleted, or its exception rows fell below the count stamped in `SYNC_DATA2` (apps hard-delete unstamped overrides without dirtying the master); `DELETED` rows purged and absent, their exception rows deleted explicitly; masters without `_SYNC_ID` (created in a calendar app) and clones (`SYNC_DATA3` not their own `_ID`, left by the provider's split) stamped a fresh handle before listing; an override created in a calendar app marks its master dirty.
  - *Fetch:* the master's rows, its overrides', reminders' and attendees', mapped to the model, merged over the projection of the phone base, applied onto the base; untouched, the base comes back byte for byte so a read is content-quiet. Each read stamps the rows converged, guarded on what it observed. On a read-only calendar the phone's change is not taken: the base projection is pushed back.
  - *Push:* the object projected as rows, master, overrides, reminders, attendees, updated in place with `CALLER_IS_SYNCADAPTER` and the account on the URI, each update selecting `_ID = ? AND (DIRTY IS NULL OR DIRTY = 0)` with an expected count of 1 and the convergence stamp in the same `applyBatch`, so a calendar-app edit racing the pass stays dirty for the next.
  - *Quiet path:* one count (`DIRTY = 1`, `DELETED = 1`, masters with `_SYNC_ID` null in our calendars), the exception rows against the masters' `SYNC_DATA2`, one store check, a member count.
- **`CalendarEngine`** routes `phone:` collections to `CalendarRemote`; a calendar pass becomes phone, server, phone; VTODO and VJOURNAL entries are never projected (the provider has no tasks) and stay the app's.

### Phase 3: in step, chosen, permitted

Exactly as `phone-contacts-mirror` does for books, which lands first:

- A switch "Also in the phone's Calendar app" on the Calendar card of both setups, on by default; per calendar in the account settings, beside its filter state. Revised 2026-10-10 by `onboarding-options-ask-on-switch`: "Show in phone calendar", off by default.
- `READ/WRITE_CALENDAR` asked when continuing with the switch on, together with the contacts permission when both are on, so one prompt. Revised 2026-10-10 by `onboarding-options-ask-on-switch`: asked as the switch is turned on, on its own.
- The same address already under another account type in `Calendars` (Google, DAVx5): not mirrored, and said.
- A debounced phone pass after every write to a mirrored calendar; Android's upload sync for calendar-app edits, which runs only with the device's auto-sync on; a pass on return; a pass on a device zone change, re-projecting floating events; the background run's offline pass as the last net, which also moves the window of the series projected as instance lists.

### Conflicts

A divergence the merge settles field by field is settled silently, on both sources. A genuine collision goes to the form `calendar-conflict-form` adds, on whichever source it arose: no side is ever dropped (agreed 2026-10-09).

## Out of scope

- Tasks and journal entries on the phone (no provider; OpenTasks and jtx Board have their own).
- Invitations sent by the phone: a calendar app adding an attendee writes an `Attendees` row and the mirror carries it into the object, but who mails the invitation is the server's scheduling (CalDAV's RFC 6638 implicit scheduling, Graph, Google), not ours.
