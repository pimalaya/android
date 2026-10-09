---
cairn: tasks
change: phone-calendar-mirror
---

# Tasks

## Phase 0
- [ ] Research agents: DAVx5 (synctools, ical4android), AOSP CalendarProvider and Etar, ICSx⁵, Fossify Calendar; revise the contract from their findings
- [ ] Agree on `docs/calendar-mapping.md` with the user

## Phase 1: Rust
- [ ] `projectEvent`: master, overrides, rule set, alarms, attendees, zones
- [ ] `applyEvent`: the managed set patched through the CST; overrides added, changed, cancelled
- [ ] Three-way merge through `IcalMerge`, conflicts named by field
- [ ] Tests: round trip byte-identical for an untouched model; each field; overrides; zones; alarms

## Phase 2: Java
- [ ] `CalendarMapping` both ways plus `merge`, JVM-tested over the contract's tables
- [ ] Accounts and `Calendars` rows reconciled; `CalendarSyncService`, `xml/syncadapter_calendar`, manifest, permissions
- [ ] `CalendarRemote`: enumerate, fetch, push, quiet path
- [ ] `CalendarEngine` routing and the phone, server, phone passes; VTODO and VJOURNAL left out

## Phase 3
- [ ] The switch in both setups and per calendar in the settings; one permission prompt
- [ ] Detection of the address under another account type
- [ ] Triggers: after writes, upload sync, on return, background
- [ ] Build: `:app:assembleDebug`, `:app:testDebugUnitTest`, `cargo test`, clippy
- [ ] Device matrix: create, edit, delete in Google Calendar and Etar; this occurrence only; this and following; a reminder firing; an event in another zone; all-day; a Graph and a Google calendar
- [ ] Fold the delta into the spec, log, CHANGELOG
