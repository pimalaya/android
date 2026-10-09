---
cairn: tasks
change: calendar-occurrence-writes
---

# Tasks

- [x] Graph: find an instance by original start, patch it from an override, delete it for an EXDATE or a cancelled override, revert a removed override
- [x] Google: the same through `events.instances` (over a window around the original start rather than `originalStart`, the identity checked as on Graph), `events.update` merged onto the instance (as the master is written) rather than `events.patch`, `events.delete`
- [x] Push: diff overrides and EXDATEs against the base, master written without overrides, one write per changed occurrence, failures per occurrence kept staged
- [x] Traits `writesOverrides`/`writesExdates` true for Graph and Google
- [x] Tests: Rust against fake Graph and Google servers (patch, delete, revert, a failure mid-way)
- [x] Build: `:app:assembleDebug`, `:app:testDebugUnitTest`, `cargo test`, clippy
- [x] Live check on the Google and Microsoft test accounts: one occurrence moved and one deleted, seen in their web calendars (done through the libraries' live suites, `ical_instances` in io-msgraph and io-gcal: moved, deleted, reverted, series and one occurrence in one push, each read back; not through the app nor the web calendars)
- [x] Permanent refusals: an occurrence the server refuses for good (4xx naming the request) answers 422, refused through `Refusals` rather than kept waiting
- [ ] Device test, fold the delta into spec/calendar.md, log, CHANGELOG
