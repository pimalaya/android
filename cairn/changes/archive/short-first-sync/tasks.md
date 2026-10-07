---
cairn: tasks
change: short-first-sync
---

## Bridge (rust/)

- [x] `Floor`: the oldest `Date` among a chunk's newest messages below a ceiling, none when fewer (listing.rs)
- [x] `mailFloor` on every backend: IMAP `UID SEARCH` below the ceiling then header fetches newest first; Graph `/messages` by `sentDateTime desc` with `$top`; Gmail `messages.list` with `before:`, metadata reads kept for the round; JMAP `Email/query` by `receivedAt` reading `sentAt`
- [x] Roles in pimdir's vocabulary: IMAP `INBOX` and RFC 6154, JMAP roles, Gmail system labels, Graph well-known folders
- [x] A store load's `date` becomes a date-only summary the driver never writes back
- [x] io-pimdir f9b13f8
- [x] Tests: the floor from the 50th date, out of order, fewer than a chunk

## App (android/)

- [x] `MailEngine`: first chunk for a mailbox never listed, the round under way or the coverage otherwise, `widen` by a chunk; `ordered` by role
- [x] `MailStore`: roles stored through `set_collection_role`, coverage with the round's floor, edges and the list's floor, the query cut at the floor
- [x] `MailList`: the floor, the row past the last message (loading, or needs the network)
- [x] `MailFill`: the k-way choice for a scroll, the fill's next mailbox and its steps
- [x] `FirstSync` and `MainActivity`: a domain's first sync on its tab's first visit, the fill started after the first dialog, on return and after a pass, stopped off the foreground, on a metered network or on an error
- [x] `PimdirStorage`: a load carries each message's date; fewer statements per new message, compiled single-value reads
- [x] `PimdirEngine.Clock` and a log line per page; `MailBridgeClockTest`
- [x] Tests: the first chunk and the band below it, the k-way floor, the fill stopping and resuming, the inbox first, a domain owed until its tab, a dated load never overwriting a summary

## Land

- [x] `cargo test`, `cargo clippy --all-targets`, `cargo fmt`; `:app:assembleDebug`, `:app:testDebugUnitTest`
- [x] Fold the delta into mail, offline-store and onboarding; log; CHANGELOG; archive
