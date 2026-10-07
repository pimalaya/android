---
cairn: tasks
change: calendar-sync-speed
---

## Bridge (rust/)

- [x] Graph: `read_entries` over `GraphReads`, events and series windows 20 to a `$batch`, alone again where the batch could not serve, a 404 left out
- [x] Google: `list_gcal_events` builds each entry's body; `EventDelta.bodies` for Google and JMAP
- [x] Test (`gcal`): a listing names each entry with its body
- [x] Tests (`graph_calendar_tests`): the batched read equals the one-by-one read, throttled batches read again alone, 120 events in 9 requests against 165, gone events left out, a window of several pages, the same request URLs as the single reads

## App (android/)

- [x] `EventDelta.bodies`; `CalendarEngine` names entries from the listing, times its bridge calls
- [x] `PimdirEngine.named`: a listed body names its member, a bound member drops it; `bound` under the store lock
- [x] `CalendarPool` and the calendar pass on it; `SyncRunner.Session` refreshes once across threads
- [x] Tests (`CalendarPoolTest`): 120 events over three calendars at 20 ms a request, side by side and batched against one by one in a row, same entries stored, no transport shared, no request under the store lock, writes serialized; bodies from the listing read nothing; a change read alone; a failure leaving the others

## Land

- [x] `cargo test`, `cargo clippy --all-targets`, `cargo fmt`; `:app:assembleDebug`, `:app:testDebugUnitTest`; `cargo deny check sources licenses`
- [x] docs/performance.md; fold into calendar; log; CHANGELOG; archive
