---
cairn: tasks
change: imap-step-fixes
---

# Tasks

Java paths are relative to android/app/src/main/java/org/pimalaya/, tests to android/app/src/test/java/org/pimalaya/. Names follow the Java domain layout (java-domain-layout): the bridge client is `client.MailClient`.

## Bridge

- [x] rust/src/mail/listing.rs: `Floor` keeps the newest `count` dates (`offer`), its reply carries them; `take_received` and the skew rule go
- [x] rust/src/mail/imap.rs: the no-`SORT` floor samples the newest UIDs, 50 then doubling, at most four widenings, until the last part adds nothing at or above the 50th

## App

- [x] `pimdir.PimdirEngine.lookup`, overridden by `mail.MailEngine`: none on IMAP or Graph, the account's own on Gmail and JMAP (`PimdirStorage.lookupObjects(links, account)`)
- [x] `mail.MailStore.releaseCrossLinked` run once per account not listed account-wide (`repairLinks`) from `MailEngine.mailboxes`
- [x] `MailClient.floor(...).dates()`, `MailEngine.newest`; `mail.ImapStep` takes the account's 50th newest across its stepped mailboxes

## Tests

- [x] Rust: imported mail among the newest UIDs (no `SORT`), the sample bound, the odd date restated
- [x] Java: two mailboxes sharing UIDs keep their own bodies; the repair releases a cross-linked body, both sides, never one its message still needs (`ImapStepTest`); the lookup within the account on an account-wide listing alone (`MailEngineTest`); the first step's account floor
- [x] e2e: `E2eMailStepTest` against the frontiers (the first step takes the ticked mailboxes alone off Gmail, mail-filter-apply; Load more on one mailbox moves its own)

## Land

- [x] CHANGELOG [Unreleased]
- [x] Build: `cargo fmt`, `cargo clippy --all-targets`, `cargo test`, `:app:assembleDebug`, `:app:testDebugUnitTest`; cairn/verify.sh
- [x] Live: e2e on google-imap
- [x] Fold the delta into spec/mail.md, log entry
