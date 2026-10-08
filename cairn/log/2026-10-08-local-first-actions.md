---
cairn: log
change: local-first-actions
landed: 2026-10-08
---

# Every local action is visible at once, the sync carries it out as staged

Commits 926681e, 7b9d997, 76d31ae (the last one also logged as ordered-write-batch).

Capabilities moved: mail (added: a sent message is in Sent at once; modified: a message is deleted into the account's trash, a mail account carries where it submits and where its trash is, a message is submitted and a copy is kept), offline-store (added: a local action is visible before any sync; modified: a connection the server dropped is reopened once).

**The seam.** The bridge carries pimdir's `Move` and `Copy` (`MutationJson`, `PimdirEngine.mutateMove` / `mutateCopy`), and the mail push carries each change out as staged: a `Remove { to }` relocates (IMAP MOVE, else COPY, `\Deleted` and `UID EXPUNGE` with UIDPLUS; Graph move; Gmail trash, untrash or label modify; JMAP `mailboxIds`), a plain `Remove` deletes for good (IMAP `UID EXPUNGE` with UIDPLUS; Graph `permanentDelete`, a bridge request; Gmail `messages.delete`; JMAP destroy), an `Add` with an origin copies server-side, one without appends. Calendar refuses a `Remove { to }`. `delete_message`, `append_sent` and the bridge's trash special cases are gone. Contacts create, edit and delete go through `mutateAdd`, `mutateEdit` and `mutateRemove` rather than direct rows.

**Mail.** A delete outside the trash is a `Move` into it, shown there at once; a delete in the trash is a permanent `Remove`, or `\Deleted` in place where the account has no trash or its session announced no UIDPLUS, the row saying it is marked deleted. Sending stages the sent copy beside the submission. Rows carry a pending mark in all three domains, and a refused one (kept in `Refusals`, cleared once a push is accepted) on messages and entries.

**Background sync removed.** `BackgroundSync`, `SyncWorker`, the WorkManager dependency, the per-book interval and the notification permission are gone; scheduled jobs and their preferences are cleared at startup. Syncing is manual until background-sync is taken up again (docs/release-plan.md item 3).

**What the implementation corrected in the delta.**
- A message's link id is its handle, not its `Message-ID`, so a move's target is never landed by link id: the move is delivered by the source's `Remove { to }` alone and the target's pending create is withdrawn once it is accepted, the arrival coming with the target's next listing.
- The sent copy lands by `Message-ID` on Gmail and Graph only, named by the create's key before the page reaches the engine; on IMAP its push is the append, waiting while the submission is queued or parked. JMAP and an account with no sent mailbox stage none. The copy is the composed message, so its `Bcc` is kept as the sender's record: the old requirement filed a copy stripped of it. The folded "A message is submitted and a copy is kept" says the submission files no copy itself, and the offline-store reopen rationale no longer mentions a filed copy.
- Whether a trash delete is permanent is decided at staging from `expungesOne`, recorded per account at each walk (an account never walked reads as erasing), and the account's record says so.
- Deleting a message still in the outbox cancels its submission and withdraws its sent copy.
- The delta's move scenario named a "move to mailbox" action the UI does not offer (out of scope): the folded scenario is a delete into the trash, with a contact saved offline beside it. The action list drops move and copy for create, edit, flag, delete, send.
- The refused mark shows on messages and entries; a contact row shows the pending mark alone.
- A removed pending create is withdrawn by io-pimdir (c5e2c65) in the same batch, applied in order by the store (log 2026-10-08-ordered-write-batch), not by a bridge workaround.

**Left open.**
- Live checks owed by the owner on a device, on test accounts only, never Posteo: one Sent copy, visible at once, on Gmail, Graph and Fastmail; permanent deletes from the trash per backend; the `Message-ID` preserved on send (a provider rewriting it would leave the staged copy pending beside its own on Gmail and Graph, or appended twice on an IMAP server that also files sent mail).
