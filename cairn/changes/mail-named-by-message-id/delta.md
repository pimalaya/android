---
cairn: delta
change: mail-named-by-message-id
---

# Delta

Folds into spec/mail.md.

## ADDED Requirements

### Requirement: A message is named by its Message-ID
A message's link id SHALL be the key pimdir assigns from its `Message-ID` (STORAGE §9): the `Message-ID` when no other message holds it, a key minted over its handle when another does, an `alt:` key when it states none. Its handle SHALL be its server's id, an IMAP UID in its mailbox or a Gmail, JMAP or Graph id, and the only id the app asks its server with. A message in two mailboxes of one account SHALL be one item in two placements, its body linked from either, never under a derived key. Its attachment records and references SHALL follow it across a move, a copy, a label and a UIDVALIDITY change.

A pending create's origin, the binding its push copies from (pimdir SYNC §3), SHALL be one of the same account holding the body the create carries, when it carries one.

#### Scenario: Two IMAP mailboxes reusing a UID
- GIVEN an IMAP account whose inbox's UID 42 carries invoice.pdf and whose archive's UID 42 carries beach.jpg
- WHEN both are read
- THEN each lists its own attachment, and the Files tab lists both

#### Scenario: A move keeps a message's links
- GIVEN a mail with an attachment, a task made from it, on IMAP or Graph
- WHEN the mail is moved to another mailbox
- THEN it keeps its attachment record and the task still opens it

#### Scenario: A message without a Message-ID
- GIVEN a message stating no `Message-ID`
- WHEN it is listed and opened
- THEN it reads as any other, its attachments shown from its body

## MODIFIED Requirements

## REMOVED Requirements

### Requirement: A link id names a message across mailboxes only where the account lists them as one
Replaced by "A message is named by its Message-ID": a link id is no longer a server id.
