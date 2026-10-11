---
cairn: log
change: mail-load-more-jmap
landed: 2026-10-11
---

# Load more on JMAP: one query a step, anchored where the last one stopped

Capabilities moved: mail (added: a JMAP step is one anchored query, a JMAP delta is read once per pass; modified: a mailbox is stored whole, a mailbox is listed a chunk at a time, older mail fills in behind, an account's frontier is where its steps reached, bodies cross the bridge as files; intro). Device test pending, so the change stays active.

**Why.** docs/mail-window-files-references-plan.md B.13, JMAP bullet: JMAP stepped from the store (`WidenStep`), widening one mailbox at a time and reading each body in two round trips.

**Step.** rust/src/client/jmap_sync.rs `step`: the `Email` state first (the checkpoint), then `Email/query` with `inMailboxOtherThan` the trash and junk ids (`inMailbox` for a filtered view), sorted `sentAt` desc when the account's mail capability lists it in `emailQuerySortOptions` else `receivedAt`, `calculateTotal`/`collapseThreads` false, limit 50, `Email/get` back-referenced with the summary properties, `receivedAt` and `blobId`. It starts after the first of the anchors the last step answered (`anchorOffset` 1); `anchorNotFound` tries the next, then pages by `receivedAt` from position 0 below the frontier plus two days. Held ids are skipped, a window held whole moving on (limit shrinks to what is left), at most `QUERIES` 8 a step. Bodies download by blob one after another; a failure cuts the answered anchors before it (`ANCHORS` 5, oldest first), a 401 fails the step; paged by reception for a `sentAt` step, an import is no anchor. `mail_step` (rust/src/ffi/mail.rs) has a JMAP branch: the roster read afresh for the trash and junk roles, items named as listed with their mailbox names, the state under `historyId`, `anchors` in the reply; the request gains `anchors` and `frontier`.

**Bodies.** `fetch_sources` JMAP branch through `Client::jmap_sources`/`sources`: one `Email/get` of the ids' `blobId`s per `maxObjectsInGet` chunk, then downloads one after another, a gone or failed one left out, 401 failing.

**Deltas.** `JmapRun` (per pool run and account, joined with `GmailRun` in `MailSession::join_run`) reads `Email/changes` and the touched emails once per start state (`jmap_mail_changes`); each mailbox's page is `project`ed by `mailboxIds`.

**App.** `MailStep.of` sends JMAP through `GmailStep`; `MailEngine.mailboxes` marks JMAP account-wide (no fill, no coverage floor); `MailEngine.stepRequest` puts `MailWindow.anchors` and the held frontier (a mailbox's own over it alone) in the request every step sends through `MailEngine.stepped` and `PimalayaClient.mailStep`; `GmailStep` keeps the reply's anchors once landed (`MailWindow.anchor`, per account and per mailbox name, forgotten with the account). io-jmap patched to git rev 919eab4.

**Tests.** Rust jmap_sync_tests.rs (first step from the top, anchored second step, anchor gone, paged fallback, held stretch, query cap, small account, failed body cutting anchors, 401, trash and junk left out with placement, mailbox view, receivedAt sort, import anchors, blobs in one get and chunked, run read once, failure not kept, sessions sharing a run) and jmap_tests.rs (projection by mailboxIds). Java: `MailWindowTest` anchors per view and forgotten, `MailStepTest` JMAP through `GmailStep` keeping anchors.

**Left open.** The device test; no live check (the Fastmail token's `pass` entry timed out on pinentry). A round over a JMAP mailbox (expired state, mailbox new since the first step) still lists its summaries from the bound. Each step reads the roster once more.
