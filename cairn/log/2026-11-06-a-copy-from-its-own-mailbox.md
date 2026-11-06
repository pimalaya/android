---
cairn: log
change: a-copy-from-its-own-mailbox
date: 2026-11-06
---

# A copy comes from its own mailbox

A fix found reading the code for the production release (production-release section 1), folded without a proposal.

Capabilities moved: mail (modified: a link id names a message across mailboxes only where the account lists them as one, a pending create's origin now held to the same account, the create's body and its `Message-ID`).

A staged copy's origin is derived, never stored (pimdir SYNC §3): the app's `BOUND_ELSEWHERE` took the first binding of the same link id on the same source, in any collection of any account. On IMAP a link id is a UID that every mailbox reuses, so copying the archive's UID 42 into Sent could push `UID COPY 42` from the inbox, another message. The statement now follows pimdir's `origin_for_link`: a based binding holding the body the create carries, a minted key paired with the identity it was minted over, and beyond it the create's own account and, for mail, its `Message-ID`. A bodiless copy of a message without a `Message-ID` still takes the first one. Test: MailMoveTest `aCopyComesFromItsOwnMailboxWhereAnotherReusesItsUid`.

Left: attachment stand-ins are keyed by account and UID (FileStore), so the reader can badge another mailbox's parts (production-release section 1).
