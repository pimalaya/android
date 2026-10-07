---
cairn: tasks
change: parallel-mailboxes
---

## App (android/)

- [x] `MailPool`: sessions per worker slot opened as needed (the caller's first), mailboxes claimed in order, failures per mailbox, one run at a time, a line per run with wall time against the summed network time; `together` for several accounts at once
- [x] `PimdirEngine.STORE`: every storage yield answered under one lock; `remoteSoFar`; the floor read counted as network
- [x] `MainActivity`: the pass, the first-sync dialog, the scroll widening and the fill on pools; the dialog counting the mailboxes landed
- [x] `MailFill.batch`: a fill step takes as many of an account as its pool runs
- [x] Tests (`MailPoolTest`): 12 mailboxes on 4 sessions against a fake with per-request latency, the inbox first and the junk last, no session shared, writes serialized under the lock, one failure leaving the others, the caller's session kept open, a fill step's batch

## Land

- [x] `cargo test`, `cargo clippy --all-targets`, `cargo fmt`; `:app:assembleDebug`, `:app:testDebugUnitTest`; `cargo deny check sources licenses`
- [x] docs/performance.md; fold into mail; log; CHANGELOG; archive
