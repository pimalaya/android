---
cairn: tasks
change: imap-step-fixes
---

# Tasks

Java paths are relative to android/app/src/main/java/org/pimalaya/, tests to android/app/src/test/java/org/pimalaya/.

## Bridge

- [ ] rust/src/client/listing.rs: `Floor` keeps the newest `count` dates (`offer`), its reply carries them; `take_received` and the skew rule go
- [ ] rust/src/client/imap.rs: the no-`SORT` floor samples the newest UIDs, 50 then doubling, at most four widenings, until the last part adds nothing at or above the 50th

## App

- [ ] `PimdirEngine.lookup`, overridden by `MailEngine`: none on IMAP, the account's own elsewhere (`PimdirStorage.lookupObjects(links, account)`)
- [ ] `MailStore.releaseCrossLinked` run once per IMAP account from `MailEngine.mailboxes`
- [ ] `PimalayaClient.mailFloorDates`, `MailEngine.newest`; `ImapStep` takes the account's 50th newest across its stepped mailboxes

## Tests

- [ ] Rust: imported mail among the newest UIDs (no `SORT`), the sample bound, the odd date restated
- [ ] Java: two mailboxes sharing UIDs keep their own bodies; the repair releases a cross-linked body; the first step's account floor
- [ ] e2e: `E2eMailStepTest` against the account frontier

## Land

- [ ] CHANGELOG [Unreleased]
- [ ] Build: `cargo fmt`, `cargo clippy --all-targets`, `cargo test`, `:app:assembleDebug`, `:app:testDebugUnitTest`; cairn/verify.sh
- [ ] Live: e2e on google-imap
- [ ] Fold the delta into spec/mail.md, log entry
