---
cairn: tasks
change: body-run-loop
---

# Tasks

Java paths are relative to android/app/src/main/java/org/pimalaya/, tests to android/app/src/test/java/org/pimalaya/.

## Investigate

- [x] Phone log and store (read-only): the looping row, its level and owed state, why Gmail returns nothing (404 on the `minimal` pre-pass), what starts each run (`wifiWatch`)

## Bridge

- [x] rust/src/client/gmail.rs `read_sources` answers `Sources {fetched, gone}`
- [x] rust/src/client/graph_mail.rs `read_sources` answers `GraphSources {fetched, gone}`
- [x] rust/src/client/imap.rs `fetch_sources` answers `ImapSources {fetched, gone}`
- [x] rust/src/client/jmap_sync.rs `sources` answers `Sources {fetched, gone}`
- [x] rust/src/ffi/mail.rs `fetchMessageSources` answers `{items, gone}`; `PimalayaClient.fetchMessageSources` the whole reply

## App

- [x] `MailEngine.sources` fills `gone`; `download` settles a gone row and marks it in `MailBodies.GONE`, not failed
- [x] `MailBodies.GONE`, `relisted`; `Run.plan` leaves out `FAILED` and `GONE`, answers its count
- [x] `MailEngine.list` and `land` call `MailBodies.relisted`
- [x] `MainActivity.wifiWatch` posts `wifiBack` on a network becoming unmetered only; `planBodies` counts what was planned
- [x] `MailList.notOnPhone`: a gone row says *Not downloaded*

## Tests

- [x] Rust: `a_raw_read_names_a_message_gone_apart_from_the_read` (gmail_tests), gone asserted in graph_mail_tests, imap_tests, jmap_sync_tests
- [x] Java `MailBodiesTest`: a body failing every attempt is not planned again until its mailbox is listed anew; a body gone is owed no more and not tried again

## Land

- [x] Spec lines in cairn/spec/mail.md, log entry
- [x] CHANGELOG [Unreleased] Fixed
- [x] Build: `cargo fmt`, `cargo clippy --all-targets`, `cargo test`, `:app:testDebugUnitTest`; cairn/verify.sh
