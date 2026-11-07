---
cairn: change
id: mail-named-by-message-id
status: active
created: 2026-11-07
---

# A message is named by its Message-ID

## Why

pimdir names an item by the identity its content states, a message by its `Message-ID` (STORAGE §2 and §9, io-pimdir `PimdirSummary::hint`), and keeps the server's id as the handle alone, the id a connector asks its server with. The app names a message by its server id instead (rust/src/mail/listing.rs `link_id: handle.clone()`, IMAP's `link_id`), so everything pimdir ties to a message's identity follows a server id:

- **IMAP.** A UID names a message in one mailbox, so the inbox's message 42 and the archive's message 42 shared one attachment record (production-release §1), fixed on the symptom by imap-link-id-per-account (6b604e4: mailbox, UIDVALIDITY and UID). A move still gives a message a new UID and a UIDVALIDITY change a new id to every message, so its attachment records and references do not follow it, and pimdir's rekey (SYNC §8), which carries placements by link id, cannot run.
- **Graph.** A message's id changes when it moves to another folder (the app asks for no immutable ids), with the same result.
- **References.** A task names the mail it came from by `Message-ID` (conventions/gtd.md `mid`), and pimdir's `source` rule matches it against link ids (STORAGE §14.2), so it never matches; the app looks the mail up on its own (`TaskStore.mailOf`).
- **Bodies.** A body is linked by link id only on Gmail and JMAP (mail.md "A link id names a message across mailboxes only where the account lists them as one"); an IMAP or Graph message in two mailboxes is fetched twice.

The owner decided on 2026-11-07 to follow pimdir: a message's identity is its `Message-ID`, the server's id its handle.

## What

- **The key.** Every backend lists a message with its `Message-ID` as the hint and its server id as the handle (an IMAP UID in its mailbox, a Gmail, JMAP or Graph id). The store assigns the key by pimdir's table (STORAGE §9): the `Message-ID` when free or held by the same message (agreeing on subject, date and sender), a `dup:` key minted over the handle when another message holds it, an `alt:` key when the message states none. So two different messages never share a key, whatever their server ids.
- **One message, several mailboxes.** A message in two mailboxes of one account is one item in two placements: one `seq`, one body, one set of attachment records. A body is linked from any mailbox holding the same identity, on every backend, never under a derived key (`lookup_objects`).
- **Links follow the message.** Attachment records (`part:<link id>#<section>`) and references (`attachment`, `sender`, `invitation`, `source`) keep their message across a move, a copy, a label or a Graph move.
- **No `Message-ID`.** Such a message lists, reads and syncs under its `alt:` key, with no attachment records and no references (pimdir's rule): its reader shows its attachments from its body, and the Files tab does not list them.
- **Undone.** imap-link-id-per-account's encoding goes: an IMAP handle is its UID in its mailbox, its link id the identity.
- **Moved to the handle or the identity**, every place reading a mail link id as a server id: the reader's fetch (MailReader `addressOf`), MailEngine's pushes and the `pushRemove` fallback, `lookup` and the body release of mail.md's requirement, `nameByMessageId` (a sent copy Gmail files under a `Message-ID` of its own), the copy origin (bound_elsewhere.sql), the outbox, Deleted items, notifications, search and the stager's `listedLinks`.

## No migration

The store is a draft (pimdir storage-draft-09): an account added before this change is removed and added again.

## Accepted

- Two messages sharing a `Message-ID` and agreeing on subject, date and sender are one item (pimdir's conservative merge, STORAGE §9): an identical message received twice shows once.
- A message sent to yourself is one item in Sent and in the inbox, sharing the first body stored; the two differ only by transport headers.

## Open

- A UIDVALIDITY change: the connector lists every message under a new UID, whose `Message-ID` the old UID's binding still holds, so pimdir mints each one a `dup:` key (STORAGE §9), a derived key with no attachment records nor references, and the round parks on the absent old bindings. pimdir's answer is its rekey (SYNC §8, io-pimdir `PimdirRekey`), carrying every placement onto its new handle by link id: a bridge entry, a rekey signal from the IMAP listing and the engine running it, a follow-up change (found while building this, 2026-11-07).
- pimdir's identity is store-wide (STORAGE §9.2): the same message in two accounts shares a `seq` and its references, its placements and attachment records listed by account.
