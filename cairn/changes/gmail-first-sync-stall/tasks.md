---
cairn: tasks
change: gmail-first-sync-stall
---

# Tasks

- [x] Diagnose from the logcat of 2026-10-10 and a read-only probe on the test account
- [x] Bridge logger at `JNI_OnLoad` (logcat, debug for the bridge, info for the libraries)
- [x] Throttle: back-off for rate and concurrency 429s, the minute only when a per-minute quota is named, `Retry after` read from Gmail's message, every wait logged
- [x] Gmail pacing per account by quota units, batches of 25 one at a time, the reader's fetch urgent
- [x] Gmail round stops a batch past its floor; envelopes carry `internalDate`
- [x] Gmail fill chunk of 100; the reader on its own executor, its writes under `PimdirEngine.STORE`; widen logged
- [x] One `PimdirDb` and `CardStore` per process
- [x] Strip: *Starting the sync* while indeterminate; one bar size
- [x] Tests: Rust classification, named retry instant, pacing per account and urgent, floor stop; Java shared store
- [x] Build: `cargo test`, `:app:assembleDebug`, `:app:testDebugUnitTest`
- [x] Fold the delta, log, CHANGELOG
- [ ] Device test: a fresh Gmail onboarding, logcat for `throttled`, `gmail paced` and the first pass's wall time
