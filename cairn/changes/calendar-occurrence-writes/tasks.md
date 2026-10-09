---
cairn: tasks
change: calendar-occurrence-writes
---

# Tasks

- [ ] Graph: find an instance by original start, patch it from an override, delete it for an EXDATE or a cancelled override, revert a removed override
- [ ] Google: the same through `events.instances` with `originalStart`, `events.patch`, `events.delete`
- [ ] Push: diff overrides and EXDATEs against the base, master written without overrides, one write per changed occurrence, failures per occurrence kept staged
- [ ] Traits `writesOverrides`/`writesExdates` true for Graph and Google
- [ ] Tests: Rust against fake Graph and Google servers (patch, delete, revert, a failure mid-way)
- [ ] Build: `:app:assembleDebug`, `:app:testDebugUnitTest`, `cargo test`, clippy
- [ ] Live check on the Google and Microsoft test accounts: one occurrence moved and one deleted, seen in their web calendars
- [ ] Device test, fold the delta into spec/calendar.md, log, CHANGELOG
