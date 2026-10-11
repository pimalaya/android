---
cairn: tasks
change: mail-sync-robustness
---

# Tasks

Java paths are relative to android/app/src/main/java/org/pimalaya/, tests to android/app/src/test/java/org/pimalaya/.

## Connections

- [x] `Transport` (android/client): drop on `IOException` and EOF, `begin`, `settle`, `reset`, `losses`
- [x] rust/src/client.rs: `Client::begin`, `settle`, `reset` upcalls; `reset` on any read or write error
- [x] throttle.rs: `Wire::wire_begin`, `wire_settle`, `wire_reset`; an answer followed to its end by its framing and settled; a reused connection lost before an idempotent request's answer reset and the request sent again once
- [x] `PimalayaClient.on()`: reopen and run again when the reply is an error and the transport lost a connection since a verb last answered (`MailSession.lostSinceAnswered`)

## Failed bodies

- [x] `MailEngine.download` answers the rows that did not land
- [x] `MailBodies.step`: failed rows put back, 3 attempts a run, `FAILED` marked, a whole failure put back, twice in a row leaving the run
- [x] `MainActivity.downloadBodies`: collects the failures, closes the account's pool after one
- [x] `MailList`: *Download failed* on a row marked failed (`mail_row_failed`, en and fr)
- [x] `GmailStep`: a body that did not land owed

## Gaps

- [x] `MailOwed` replaces `MailBodies.ARRIVALS`, persisted, by link id
- [x] `MailWindow`: a mailbox's own frontier (`since(context, accountId, collection)`, `more(context, accountId, collection)`, `moveBackMailbox`, `stepped`), forgotten with the account, dropped by a release (`dropMailboxes`)
- [x] `RemotePass.stepMail` through `MailWindow.stepped`: a step over one mailbox moves that mailbox's frontier
- [x] The floor (`MailStore.floorOf`), the planner, the row, the footer, `MessageView` and `WidenStep` read the mailbox's frontier
- [x] Junk and trash listed when opened alone (`MailList` asks, `MainActivity.listAlone`)

## Tests

- [x] Rust throttle.rs: resend after EOF on a reused connection, none for a POST or a fresh connection, an answer settled at its end, a `Connection: close` or cut answer never settled, a chunked answer settled
- [x] Java `TransportTest` (client): a server answering once then closing, the next read reconnecting; `reset`, `begin` on an unsettled connection
- [x] `MailBodiesTest`: a failed body retried in the run, marked failed after 3 attempts, a whole failure put back and an account leaving after two; owed bodies persisted
- [x] `MailWindowTest`: a step over one mailbox moves its own frontier, the older of the two read, dropped and forgotten
- [x] `MailStepTest`: a step's body that did not land is owed in every mailbox it was placed in; `PimalayaClientTest`: a failed reply told by its error field

## Land

- [x] CHANGELOG [Unreleased] as a net diff
- [x] Build: `cargo test`, `cargo fmt`, `cargo clippy`, `:app:assembleDebug`, `:app:testDebugUnitTest`, `:client:testDebugUnitTest`; cairn/verify.sh
- [x] Fold the delta into spec/mail.md, log entry
- [ ] Device test (the user): Microsoft 365 after two idle minutes (every body in the frontier downloads), a Gmail spam view opened, *Load more* in one label then the merged view, an arrival dated in the past across a restart
- [ ] Archive once the device test passes
