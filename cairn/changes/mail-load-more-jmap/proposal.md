---
cairn: change
id: mail-load-more-jmap
status: active
created: 2026-10-11
---

# Load more on JMAP: one query a step, anchored where the last one stopped

## Why

`mail-load-more` landed the generic step contract and Gmail; JMAP still steps from the store (`WidenStep`): it widens one mailbox at a time by `Email/query` per mailbox sorted by `receivedAt`, then reads every body in two round trips (`Email/get` for the `blobId`, then the download). docs/mail-window-files-references-plan.md section B.13 settles the JMAP step: the account is one query, as a Gmail account is one listing, and a message filed in several mailboxes is one `Email` with one id, read once.

## What

- **The step** (rust/src/client/jmap_sync.rs, `mail_step`'s JMAP branch): one `Email/query` over the account with `inMailboxOtherThan [trash, junk]` (`inMailbox` for a view filtered to one mailbox), sorted `sentAt` descending when the mail capability's `emailQuerySortOptions` lists it, else `receivedAt`, `calculateTotal false`, `collapseThreads false`, limit about 50, back-referenced by `Email/get` with the summary properties, `receivedAt` and `blobId`. Each `Email` the store does not hold is downloaded by its blob, one after another on the session's connection, and placed in every mailbox its `mailboxIds` names. The checkpoint is the account's `Email` state, read before the query.
- **Anchors.** A step answers the ids its query reached last, oldest first (`anchors`); the next step queries from the first with `anchorOffset 1`. `anchorNotFound` (the message deleted or moved to the trash) retries with the next one, then falls back to paging by `receivedAt` below the frontier with a two-day margin, skipping what the store holds. A window the store already holds whole moves on, a few queries at most. A body that fails cuts the anchors before it, so the next step reads it again. The app keeps the anchors beside the frontier (`MailWindow.anchors`), per account and per mailbox a view steps alone, saved once the step landed.
- **Account-wide.** A JMAP account is listed account-wide as Gmail is (`MailStore.markAccountWide`): no background fill, no coverage floor from its mailboxes, steps through `GmailStep` (`MailStep.of`).
- **Deltas once per pass.** A pass's mailboxes share one `Email/changes` replay from a given state, with one `Email/get` of the touched messages, per pool run (`JmapRun`, joined as `GmailRun` is), each mailbox projecting it by `mailboxIds`: a member while filed there, gone otherwise.
- **Bodies.** `fetchMessageSources` on JMAP reads the `blobId`s of all the ids in one `Email/get` (chunked by `maxObjectsInGet`), then downloads the blobs one after another into files: no `Email/get` per message.
- **io-jmap** pinned by git rev 919eab4 (anchor, anchorOffset, calculateTotal, collapseThreads, `sentAt` comparator, typed `anchorNotFound`, the mail capability) until released.

## Out of scope

- A round over a JMAP mailbox (an expired state, a mailbox new since the first step) still lists its summaries from the account's bound, as before; it lists headers, no body.
- Junk and trash on demand, IMAP and Graph steps (their own changes).
