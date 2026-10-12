---
cairn: delta
change: drop-sync-mark
---

Folds into `spec/offline-store.md` and `spec/mail.md`.

## ADDED Requirements

## MODIFIED Requirements

### Requirement: A local action is visible before any sync
Every action a user takes on an item (create, edit, flag, delete, send) SHALL be staged as a pimdir mutation, never as a direct row write, and SHALL show in every list it concerns at once, a relocation's target included. The bridge SHALL carry pimdir's `Move` and `Copy` beside `Add`, `Edit`, `SetFlags` and `Remove`, and the sync SHALL carry each staged change out as staged (pimdir SYNC section 4): a `Remove` naming a destination as a server move, one naming none as a server delete, an `Add` with an origin as a server-side copy and one without as an append, never one turned into another. A connector that cannot carry one out, a relocation into another account's collection or any calendar relocation, SHALL reject it. A staged removal SHALL be listed nowhere. A row SHALL carry no mark of whether its change was pushed yet.

#### Scenario: A message deleted offline
- GIVEN a message in the inbox of an account with a trash
- WHEN it is deleted, offline
- THEN it shows in the trash at once, and no longer in the inbox
- AND the next sync moves it on the server

#### Scenario: A contact saved offline
- GIVEN no network
- WHEN a contact is created in an account's address book
- THEN it is listed at once, staged as an `Add`

### Requirement: A message is deleted into the account's trash
Deleting a message outside the trash SHALL stage a move into the mailbox the account records as its trash: the row SHALL leave its mailbox and show in the trash at once. The next sync SHALL relocate it as staged: on IMAP with MOVE, or where the server implements no MOVE with COPY and `\Deleted`, then `UID EXPUNGE` where it announces `UIDPLUS`; on Graph into the folder Graph names `deleteditems`, recognised by id when the roster is read; on Gmail with `messages.trash`; on JMAP through `mailboxIds`.

Deleting a message in the trash SHALL stage a removal, pushed as a permanent delete: on IMAP `\Deleted` then `UID EXPUNGE` (RFC 4315); on Graph `permanentDelete`; on Gmail `messages.delete`; on JMAP `Email/set` destroy. Where an IMAP account records no trash, or the message sits in the trash of an account whose last session announced no `UIDPLUS` (an expunge without it is mailbox-wide), a delete SHALL stage `\Deleted` in place: the row stays, its line saying it is marked deleted, and the toast says so. An account no session recorded yet SHALL read as announcing it. A JMAP account recording none SHALL refuse the delete outright: RFC 8621 has no counterpart to `\Deleted`. Deleting a message still in the outbox SHALL cancel its submission and withdraw its staged sent copy, there being nothing on the server to tell.

Deleting SHALL ask first, from the reader and from a selection alike. Where the delete removes messages for good and the phone holds no body of some of them, the question SHALL say how many: Deleted items keeps only what was stored, so those cannot be restored, and it SHALL say that opening them or downloading the trash keeps them restorable.

#### Scenario: An account with a trash
- GIVEN an account recording a mailbox marked `\Trash`
- WHEN a message is deleted
- THEN the row leaves the list and shows in the trash at once
- AND the next sync moves it there

#### Scenario: Emptying a trash never opened
- GIVEN a selection of 12 messages in the trash, 9 of them never opened
- WHEN delete is pressed
- THEN the question says 9 of them are not on this phone and cannot be restored once deleted

#### Scenario: A message already in the trash
- GIVEN a message in the trash of a server announcing `UIDPLUS`
- WHEN it is deleted
- THEN the next sync expunges that one message, and no other

#### Scenario: An IMAP account with no trash
- GIVEN an account recording none
- WHEN a message is deleted
- THEN `\Deleted` is staged where it is, the reader is told so, and the row stays, marked deleted

#### Scenario: A JMAP account with no trash
- GIVEN a JMAP account recording none
- WHEN a message outside a trash is deleted
- THEN the delete is refused saying so, and nothing is staged

### Requirement: A sent message is in Sent at once
Sending SHALL stage the message as composed into the mailbox the account marks as its sent mail, beside its submission, so Sent shows it before any sync. Gmail and Graph file sent mail themselves: before a page reaches the engine, an arrival carrying the `Message-ID` of a pending create in that mailbox SHALL be named by the create's key, so the listing lands the staged copy and nothing is pushed there. Gmail stamps a `Message-ID` of its own on what it sends: the submission SHALL read Gmail's sent copy back for it, and an arrival carrying that one SHALL be named by the create staged under the composed one. On IMAP the staged copy's push SHALL be the append, made only once the submission went (A message is submitted and a copy is kept). A JMAP account, which sends nothing yet, and an account marking no sent mailbox SHALL stage none. While the submission is in the outbox, a list showing the outbox SHALL list its outbox row alone, not the staged copy; discarding the outbox row SHALL withdraw the copy with it.

#### Scenario: Sent offline
- GIVEN a message sent with no network
- WHEN the Sent mailbox is opened
- THEN the message is there

#### Scenario: A provider filing sent mail itself
- GIVEN a message sent from a Graph account
- WHEN the next sync lists `Sent Items`
- THEN the staged copy is landed on Graph's own copy, and Sent holds one

#### Scenario: Gmail renaming what it sends
- GIVEN a message sent from a Gmail account, which Gmail sends under a `Message-ID` of its own
- WHEN the next sync lists `SENT`
- THEN the staged copy is landed on Gmail's copy, and Sent holds one

### Requirement: A push converges on a message gone
A write on one message that the server answers 404 (a message deleted for good, purged from the trash or the spam, or a Gmail draft saved under a new id since the listing) SHALL be taken as converged: a removal, a move and a marker accepted, a move's target create withdrawn, and a copy of it refused. The bridge SHALL answer such a write as done on Gmail and Graph, and a JMAP `notFound` likewise, a copy keeping its 404. A push SHALL NOT fail a pass with a 404, so only a mailbox's own reads are taken as the server refusing it.

#### Scenario: A message purged between the listing and the push
- GIVEN a deleted message staged for removal, purged from the trash on the server after the pass listed it
- WHEN the pass pushes the removal
- THEN it is accepted, and the page the pass listed lands

#### Scenario: A copy of a message gone
- GIVEN a copy staged of a message the server no longer holds
- WHEN it is pushed
- THEN it is refused

## REMOVED Requirements
