---
cairn: tasks
change: graph-contact-reads
---

## Bridge (rust/)

- [x] `read_cards` over `GraphCardReads`, 20 to a `$batch`, alone again where the batch could not serve, a 404 left out; `Client::read_graph_cards`
- [x] `$batch` helpers shared with the calendar in `client/graph.rs`
- [x] `Native.readGraphCards` (ffi/card.rs)
- [x] Tests (`graph_tests`): the batched read builds the cards the one-by-one read built, a throttled batch read again alone, only the refused requests (429, 503, 500) read again, 120 changed contacts in 6 requests against 120, a gone contact left out (in the batch and before its lone read), another lone failure failing the read, batch URLs equal to the single read's request line

## App (android/)

- [x] `PimalayaClient.readGraphCards`; `CardDelta` constructor public as `EventDelta`'s
- [x] `OfflineEngine.fetchGraph`: listing first, one batched read for the rest, the fetch line; enumerate timed toward the page clock
- [x] Tests (`OfflineEngineGraphFetchTest`): 120 handles in one batched call and never `readCard`, a gone contact left out without an error, a complete round reading only what its listing lacks

## Land

- [x] `cargo test`, `cargo clippy --all-targets`, `cargo fmt`; `:app:assembleDebug`, `:app:testDebugUnitTest`; `cargo deny check sources licenses`
- [x] docs/performance.md; fold into carddav-sync; log; CHANGELOG; archive
