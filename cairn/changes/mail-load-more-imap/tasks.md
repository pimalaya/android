---
cairn: tasks
change: mail-load-more-imap
---

# Tasks

Java paths are relative to android/app/src/main/java/org/pimalaya/, tests to android/app/src/test/java/org/pimalaya/.

## Bridge

- [x] rust/src/client/imap.rs: `ImapWire` (the transport an `ImapSession` runs over), so a scripted server drives it without a JVM
- [x] Floor by `UID SORT (REVERSE DATE)` with `SENTSINCE` widened 90 days, a year, all; the no-`SORT` sample ignoring dates more than a day before `INTERNALDATE` (`Floor::take_received`)
- [x] Search cache per (mailbox, UIDVALIDITY, scope), header fields reused, dropped on UIDNEXT, HIGHESTMODSEQ or EXISTS moving
- [x] `fetch_sources`: sizes, then streamed batches of 50 messages or about 12 MB to files, a refused batch read one by one
- [x] rust/src/ffi/mail.rs: the `is_imap` branch of `fetch_sources`

## App

- [x] `ImapStep`: first step (inbox floor, a round per mailbox since it, bodies), later steps (`WidenStep`'s widening, then the bodies brought)
- [x] `MailStep.of`: IMAP takes `ImapStep`
- [x] `MailEngine.syncSince`: one mailbox listed from a given floor

## Tests

- [x] Rust (imap_tests.rs): SORT floor, SORT window widening, no-SORT floor and the odd date, search cache dropped on UIDNEXT, batched bodies to files, a refused batch read one by one
- [x] Java (`ImapStepTest`): first step from the inbox floor with bodies, a later step bringing its bodies

## Land

- [x] CHANGELOG [Unreleased]
- [x] Build: `cargo test`, `cargo fmt`, `cargo clippy`, `:app:assembleDebug`, `:app:testDebugUnitTest`; cairn/verify.sh
- [x] Fold the delta into spec/mail.md, log entry
- [ ] Live check on the Fastmail test account (skipped: the password store's gpg pinentry timed out)
- [ ] Device test (the user): a fresh IMAP account on Wi-Fi, *Load more* twice, logcat for `mail widen` floors and body batches
- [ ] Archive once the device test passes
