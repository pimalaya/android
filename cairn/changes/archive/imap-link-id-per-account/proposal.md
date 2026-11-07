---
cairn: change
id: imap-link-id-per-account
status: abandoned
created: 2026-11-06
---

# An IMAP message's link id is unique in its account

Abandoned, superseded by cairn/changes/mail-named-by-message-id (2026-11-07). Its encoding was built (6b604e4) and removed by that change: pimdir names a message by its `Message-ID`, not by a server id, so the collision this change answered cannot occur. Its delta is not folded.

## Why

An IMAP message's link id was its bare UID, which every mailbox reuses, while pimdir assumes a link id names one message in its account. Attachment stand-ins are keyed `part:<link id>#<section>` and listed by account and link id (FileStore `partKey`, `recordAttachments`, `attachments`; pimdir STORAGE §14.3), so the inbox's UID 42 carrying invoice.pdf and the archive's UID 42 carrying beach.jpg shared one record. Reproduced in the store on 2026-11-06: message 42 listed invoice.pdf and beach.jpg was never recorded (production-release section 1). The store held one stand-in for two messages, so no reader-side fix could separate them. The same UID also shared one `seq` and one reference endpoint (a sender, an invitation) across mailboxes.

## What

The owner's decision, 2026-11-06: an IMAP message's link id becomes unique in its account, built from its mailbox, the mailbox's UIDVALIDITY and its UID; its handle stays the UID within its mailbox. Gmail, JMAP and Graph are unchanged, their link id being their handle.

- **The encoding.** An IMAP listing names each member `<UID>:<UIDVALIDITY>:<mailbox>` (rust/src/mail/imap.rs `link_id`), `42:1700000000:INBOX`. The UID and the UIDVALIDITY are decimal digits, so the first two colons split them off and the mailbox, last, may hold any `/`, `#` or `:`. Beginning with a digit, the key never begins with a prefix pimdir reserves (`alt:`, `hash:`, `dup:`, `part:`, `file:`, `esc:`, STORAGE §9): it is never escaped, never read as writer-derived (ItemLinks `derived`), so its message gets its stand-ins, and a key minted over it (`dup:<key>#<handle>`) stays apart from it.
- **Filed as listed.** The store filed every message under its handle, a pending create landed onto an arrival taking the arrival's handle as its identity, which would have filed every IMAP message under its bare UID again. It now files a message under the link id its listing names it by: MailEngine remembers each page's names before a landing renames one (`listedLinks`) and hands them to the write (PimdirStorage `applyWrites`), so a landed sent copy, copy or append takes the arrival's link id, and a bound handle keeps the one it has. For Gmail, JMAP and Graph the name is the handle, as before.
- **The handle where the server is asked.** Every place reading an IMAP link id as the UID now reads the handle: the reader's fetch (MailReader `addressOf`), and the withdrawn target of a move whose remove carries no link id (MailEngine `pushRemove` reads the source's bound link id, not the handle). Already on the handle and left as they are: MailEngine's pushes (flags, relocate, copy, destroy, append), its body fetches (`handleFor`), MailStager, `nameByMessageId`, the copy origin (bound_elsewhere.sql answers a binding's handle; its Message-ID guard stays for the stores and backends it served), Deleted items (a restore stages by link id and is appended), notifications, search and the outbox (keyed by the store's link id or `seq`).
- **No body links on IMAP**, as before (MailEngine `lookup`): each IMAP link id names one mailbox's message.

## No migration

Stores written before hold their IMAP messages under bare UIDs. No migration is written: the store is a draft (pimdir storage-draft-09), and an IMAP account added before this change is removed and added again. A store kept as it is keeps its old rows under their UIDs, a bound handle never changing its link id, so it keeps the collision until the account is added again.

## Open

- UIDVALIDITY is part of the key. After a UIDVALIDITY change a round lists every message under a new link id while the store keeps a bound handle's old one, as it did before this change; pimdir's rekey (SYNC §8), which the app does not run, could not carry state across such a change by link id.
- Two IMAP accounts holding the same mailbox name, UIDVALIDITY and UID share a link id, and with it a `seq` and reference endpoints, which pimdir scopes by kind, not by account (STORAGE §9.2); their attachments, listed by account, stay apart.
- The owner checks it on the device: an IMAP account added again, two mailboxes sharing a UID each showing its own attachment, a sent message's copy listed once in Sent.
