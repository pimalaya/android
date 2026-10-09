---
cairn: tasks
change: phone-calendar-mirror
---

# Tasks

## Phase 0
- [x] Research agents: DAVx5 (synctools, ical4android), AOSP CalendarProvider and Etar, ICSx⁵, Fossify Calendar; revise the contract from their findings
- [ ] Settle `docs/calendar-mapping.md` (delegated to the supervisor, 2026-10-09)

## Phase 1: Rust
- [ ] `projectEvent`: master, overrides, rule set, alarms (end-related, absolute, repeating converted), attendees, zones; objects without a master as standalone overrides
- [ ] Series the provider cannot show projected as an `RDATE` instance list over the window (`IcalRecurSet`)
- [ ] `applyEvent`: the managed set patched through the CST; overrides added, changed, removed, cancelled; `DTSTAMP` refreshed, `SEQUENCE` bumped for organized group events
- [ ] Three-way merge through `IcalMerge`, conflicts named by field
- [ ] Tests: round trip byte-identical for an untouched model; each field; overrides; zones; alarms; instance lists

## Phase 2: Java
- [ ] `CalendarMapping` both ways plus `merge`, JVM-tested over the contract's tables: civil-time diff, parsed rule sets, the provider's cell formats
- [ ] Accounts, the `Colors` palette and `Calendars` rows reconciled (name and colour kept in `CAL_SYNC1`, `CAL_SYNC2`); `CalendarSyncService`, `xml/syncadapter_calendar`, manifest, permissions; a phone pass before account removal
- [ ] `CalendarRemote`: enumerate (masters, created and cloned rows stamped, exception counts), fetch, guarded push in place, read-only revert, quiet path
- [ ] `CalendarEngine` routing and the phone, server, phone passes; VTODO and VJOURNAL left out

## Phase 3
- [ ] The switch in both setups and per calendar in the settings; one permission prompt
- [ ] Detection of the address under another account type
- [ ] Triggers: after writes, upload sync, on return, on a device zone change, background
- [ ] Build: `:app:assembleDebug`, `:app:testDebugUnitTest`, `cargo test`, clippy
- [ ] Device matrix: create, edit, delete in Google Calendar, Etar and Fossify; this occurrence only; this and following; an occurrence reverted; a reminder firing; an event in another zone; all-day; a read-only calendar edited; a Graph and a Google calendar
- [ ] Fold the delta into the spec, log, CHANGELOG
