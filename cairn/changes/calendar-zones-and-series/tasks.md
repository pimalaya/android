---
cairn: tasks
change: calendar-zones-and-series
---

# Tasks

- [x] `expand` on `IcalRecurSet`, each occurrence with its identity, start and zone
- [x] Non-IANA `TZID`s resolved by `IcalTz` from the object's `VTIMEZONE`
- [x] Java: occurrence to instant (IANA, CLDR Windows table, offset, UTC, floating); the agenda in the device's zone
- [x] `write` keeps a replaced date's `TZID` and `VALUE`
- [x] `create` in the device's zone with a `VTIMEZONE` built from `ZoneRules`
- [x] Entry page: this occurrence, this and following, or all, on save and delete; override written or patched; `EXDATE` on delete
- [x] This and following: the split (`UNTIL` on the master, `COUNT` converted, overrides and `EXDATE`s moved, new object)
- [x] Tests: Rust (set, zones, overrides, `EXDATE`, write keeping `TZID`), JVM (instants, Windows table, `VTIMEZONE` built)
- [x] Build: `:app:assembleDebug`, `:app:testDebugUnitTest`, `cargo test`, clippy
- [ ] Device test: a Graph and a Google event at their right hour; a New York event read in Paris; one occurrence moved and one deleted, seen on the server
- [ ] Fold the delta into `spec/calendar.md`, log, CHANGELOG, tick docs/production-review.md
