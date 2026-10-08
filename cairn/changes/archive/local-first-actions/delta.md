---
cairn: delta
change: local-first-actions
---

## ADDED Requirements

### Requirement: A local action is visible before any sync
Every action a user takes on an item (create, edit, flag, move, copy, delete) SHALL be staged as a pimdir mutation and SHALL show in every list it concerns at once, its target included. The sync SHALL carry each staged change out as staged: a relocation as a server move or copy, a delete as a server delete, never one turned into the other. A visible row whose placement is created or changed and not pushed yet SHALL carry a pending mark, a moved item's on its target's row; a staged removal is listed nowhere and carries none. A change the server refused for good SHALL carry a refused mark.

#### Scenario: A message moved
- GIVEN a message in the inbox
- WHEN it is moved to another mailbox, offline
- THEN it shows in that mailbox at once with a pending mark, and no longer in the inbox
- AND the next sync moves it on the server and the mark goes

### Requirement: A sent message is in Sent at once
Sending SHALL stage the message into the account's Sent collection beside its submission, so Sent shows it before any sync. Where the provider files sent mail itself, its listing SHALL land the staged copy, matched on the `Message-ID`; elsewhere the staged copy's push SHALL be the append, made only once the submission went. While the submission is in the outbox, a list showing the outbox SHALL list its outbox row alone, not the staged copy.

#### Scenario: Sent offline
- GIVEN a message sent with no network
- WHEN the Sent mailbox is opened
- THEN the message is there, marked pending

## MODIFIED Requirements

### Requirement: A message is deleted into the account's trash
Deleting a message outside the trash SHALL stage a move into the mailbox the account records as its trash: the row SHALL leave its mailbox and show in the trash at once. The next sync SHALL relocate it with MOVE, or where the server implements no MOVE with COPY and `\Deleted`, then `UID EXPUNGE` where it announces `UIDPLUS`.

Deleting a message in the trash SHALL stage a removal, pushed as a permanent delete: on IMAP `\Deleted` then `UID EXPUNGE` (RFC 4315), where the account's last session announced `UIDPLUS`; on Graph `permanentDelete`; on Gmail `messages.delete`; on JMAP `Email/set` destroy. Where an IMAP account records no trash, or its server announced no `UIDPLUS` (an expunge without it is mailbox-wide), a delete SHALL stage `\Deleted` in place: the row stays, marked deleted, and the toast says so. A JMAP account recording none SHALL refuse the delete outright: RFC 8621 has no counterpart to `\Deleted`.

#### Scenario: An account with a trash
- GIVEN an account recording a mailbox marked `\Trash`
- WHEN a message is deleted
- THEN the row leaves the list and shows in the trash at once, marked pending
- AND the next sync moves it there

#### Scenario: A message already in the trash
- GIVEN a message in the trash of a server announcing `UIDPLUS`
- WHEN it is deleted
- THEN the next sync expunges that one message, and no other

#### Scenario: A JMAP account with no trash
- GIVEN a JMAP account recording none
- WHEN a message outside a trash is deleted
- THEN the delete is refused saying so, and nothing is staged

## REMOVED Requirements
