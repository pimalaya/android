---
cairn: tasks
change: fill-from-two-queues
---

# Tasks

- [x] A first chunk and a step of 25 (`MailEngine.FIRST_CHUNK`, `MailStep.COUNT`); the bridge takes both counts from the app's requests, the IMAP unsorted sample now 400 headers at most
- [x] First sync on IMAP and Graph: every ticked mailbox's first chunk side by side on the whole pool (`SyncPasses.firstListing` over `fetchMail`), the frontier at the account's 25th newest (`MailStore.firstFrontier`); `ImapStep`'s first path and `MailEngine.newest` removed
- [x] Listing queue: `MailFill.Queue` (never-listed first, a mailbox under way never taken twice, a failure stopping it) taken by free jobs (`WorkerPool.drain`), no step barrier; its own thread beside the body step's io thread
- [x] Body loop apart, replanned as chunks land; one session while the fill lists, the account's whole pool after (`SyncCoordinator.bodyPool`)
- [x] Dock line from the mailboxes the listing jobs hold
- [x] Java tests: first-sync frontier, refusal, small account, a step from the store asking nothing of the server (`MailboxStepsTest`), the queue (`MailFillTest`), a free worker taking the next key (`MailPoolTest`), the counts at 25; `testDebugUnitTest` and `assembleDebug` green
- [ ] Device run on the Microsoft test account (the user runs it): time from sign-in to the first rows, to the first sync's end, and the fill's wall time, against 2026-11-05's
- [x] Second device run (2026-11-05 09:32): first rows waited for the frontier (the list read the 1st of the month as the window before the first sync set one), Load more ran one network step per mailbox (20 s), Graph answered 429 MailboxConcurrency as the 25 bodies and three listings started together
- [x] The list floor ignores a window no first sync set (`MailWindow.held`), so first chunks show as they land
- [x] Every account but Gmail and JMAP steps from the store (`WidenStep`), its bodies to the body step; `ImapStep`, `GraphStep` and `MailEngine.graphStepRead` removed; the footer takes one step per account
- [x] The listing starts once the body step is done (`SyncCoordinator.startListing`)
- [x] Opening a message that has no body logs why (`MailReader`)
- [x] Third device run (2026-11-05 10:04): first rows at 5.4 s, the first sync's envelopes at 7.3 s, then the dock went while the bodies downloaded (7.6 s on four throttled Graph sessions)
- [x] The 25 bodies download within the first sync's and Load more's pass on one session, the bar indeterminate (`MailEngine.downloadStep`, `SyncPasses.stepBodies`, `Progress.uncounted`); the list told when a frontier moves (`MailState.moved`)
- [x] The fill's listing silent: no dock, no line; the envelope-listing line and its strings removed
- [x] Graph's background bodies on one session (`SyncCoordinator.bodyPool`)
- [x] JMAP on the same phases: `MailStepKind::Store` in rust/src/account.rs (Graph too, the `Mailboxes` kind removed), chunked and filled like IMAP (`MailEngine.scopeOf`, `MailStore.Edge.allMail` for Gmail alone); its tests moved
- [x] Load more refused with a toast while the fill lists (`mail_more_filling`), then only when the store lacks the step (`MailStore.stepHeld`, user 2026-11-05)
- [x] The dock's pulsing download glyph removed, its spinner kept (`ic_download` gone)
- [x] Fourth device run (2026-11-05 12:50 to 12:57): Fastmail first chunks 1.96 s on 4 sessions, 25 JMAP bodies 5.0 s, fill 1.3 s; Microsoft first chunks 5.65 s, 25 bodies 2.2 s on one session, fill 31.5 s with no throttle; Gmail step 9 s; no throttle, failure or on-demand fetch
- [x] Gmail kept as it is, its costs recorded (proposal, Per backend)
- [ ] Device run on JMAP (Fastmail test account) and IMAP
- [ ] The Rust bridge's Graph step (the account walk behind `MailClient.graphStep`) and the footer's per-mailbox wording, both unused now, removed; the JMAP step too. Dropping the two arms of `MailSession::step` (rust/src/mail/session.rs) leaves dead, by rustc on 2026-11-06: `MailSession::jmap_step` and `graph_step`, `MailStepReply::Graph`, `GraphStepItem`, most of rust/src/mail/jmap_step.rs (the walk, `StepRequest` and its fields), `Jmap::step`, `email_state`, `step_query`, `STEP_PROPERTIES` in jmap.rs, `Msgraph::step`, `list_step`, `walk`, `dated`, `StepAsk`, `StepMessage`, `Stepped`, `STEP_SELECT`, `STEP_SLACK`, `inefficient`, `with_ties`, `placed` in msgraph.rs, and on the Java side `MailClient.graphStep`, GraphStepReply.java, GraphStepItem.java, with their tests
- [ ] Decide the open point on a session waiting for its own write from the device numbers
- [ ] Fold into cairn/spec/mail.md, log, CHANGELOG
