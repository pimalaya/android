---
cairn: tasks
change: mail-load-more-jmap
---

# Tasks

Java paths are relative to android/app/src/main/java/org/pimalaya/, tests to android/app/src/test/java/org/pimalaya/.

## Bridge

- [x] rust/Cargo.toml: io-jmap patched to git rev 919eab4; `JmapEmailQueryOptions` literals take its new fields
- [x] rust/src/client/jmap_sync.rs: `step` over a `JmapStepReads` source (state, anchored query, blob download), anchors, `anchorNotFound` retries, received-date fallback, held windows moved past, a failed body cutting the anchors
- [x] rust/src/client/jmap.rs: `JmapStepReads` for `JmapCalls`, `Client::jmap_step`, `Client::jmap_sources` (one `Email/get` for the blob ids, downloads one after another); deltas through `JmapRun`
- [x] `JmapRun`: one `Email/changes` replay and `Email/get` of the touched messages per pool run and start state, joined with the session's run (rust/src/ffi/session.rs)
- [x] rust/src/ffi/mail.rs: `mail_step` JMAP branch (`anchors`, `frontier` in the request, `anchors` in the reply, the `Email` state as `historyId`), `fetch_sources` JMAP branch

## App

- [x] `MailStep.of`: JMAP steps through `GmailStep`; `MailEngine.mailboxes` marks a JMAP account account-wide
- [x] `MailEngine.stepRequest` carries a JMAP account's anchors and frontier through `MailEngine.stepped` and `PimalayaClient.mailStep`
- [x] `MailWindow.anchors`: kept per account and mailbox, saved once the step landed (`GmailStep`), forgotten with the account

## Tests

- [x] Rust: first step from the top, anchored second step, anchorNotFound retry then fallback, held window moved past, failed body cuts anchors, placement in every mailbox, receivedAt sort without sentAt; delta replayed once and projected per mailbox; sources by one Email/get
- [x] Java: `MailWindowTest` anchors kept per scope and forgotten; `MailStepTest` a JMAP step through `GmailStep`

## Land

- [x] CHANGELOG [Unreleased]
- [x] Build: `cargo test`, `cargo fmt`, `cargo clippy`, `:app:assembleDebug`, `:app:testDebugUnitTest`; cairn/verify.sh
- [ ] Live check on the Fastmail test account (read-only), if a token is reachable without a prompt (skipped: pinentry timeout)
- [x] Fold the delta into spec/mail.md, log entry
- [ ] Device test (the user): a fresh Fastmail account, first step, *Load more* twice, a mailbox view's *Load more*
