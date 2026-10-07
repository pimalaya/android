---
cairn: tasks
change: graph-unfiltered-delta
---

## Bridge (rust/)

- [x] `GraphFolder`: `/messages` by band, the unfiltered delta, messages read by id (`$batch` of 20, a GET the batch did not serve sent again alone); the live Graph behind it
- [x] `list_folder`: a delta from the unfiltered link (out-of-scope changes dropped, members in scope unnamed); a band round and a first chunk by `/messages`, resumed below the oldest `Date` reached; a round over a covered scope as the delta's first pass, the link on its last page; a filtered link or an old cursor refused
- [x] `Named::unnamed`, `MailRequest.covered`, `Native.nameMessages`
- [x] Tests over a fake Graph through io-pimdir's std engine and store: exact band filter, the first chunk before any delta, three widenings listing each band once, out-of-scope changes dropped, a change before the link kept, an interrupted first pass resumed, an expired link, ties across a page, an old link refused, an undated message across a widening (ignored until io-pimdir is fixed)

## App (android/)

- [x] `MailEngine`: `covered` in the request, unnamed members named by the store or read by id, `scope_bound` false for every backend
- [x] `MailNamingTest`

## Land

- [x] `cargo test`, `cargo clippy --all-targets`, `cargo fmt`; `:app:assembleDebug`, `:app:testDebugUnitTest`
- [x] docs/performance.md; fold into mail; log; CHANGELOG; archive
