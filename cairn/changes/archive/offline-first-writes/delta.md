---
cairn: delta
id: offline-first-writes
---

# Delta

## ADDED Requirements

### Requirement: An action is a local write
Every action the reader takes in any domain SHALL be applied to the store alone and SHALL succeed with no network. Only a sync pass and the fetch of a message body SHALL reach a server.

#### Scenario: A marker written with the radio off
- GIVEN no network
- WHEN the reader marks a message read
- THEN the store records it, the list reflects it, and nothing is reported as failed

#### Scenario: An entry edited with the radio off
- GIVEN no network
- WHEN a calendar entry is saved
- THEN the agenda shows the edit and the push waits for the next sync

#### Scenario: The push is refused later
- GIVEN a staged write the server rejects
- WHEN the sync pushes it
- THEN the sync reports the refusal, the write staying staged

### Requirement: A mail account carries where its trash and sent mailboxes are
A mail account SHALL store the mailboxes the server marks `\Trash` and `\Sent` (RFC 6154), refreshed by every mail sync, so a delete decides between a move and a marker with no round trip.

#### Scenario: An account with a trash
- GIVEN an account whose walk found a mailbox marked `\Trash`
- WHEN a message is deleted offline
- THEN the delete is staged as a move into it and the row leaves the list

#### Scenario: An account with none
- GIVEN an account marking no mailbox `\Trash`
- WHEN a message is deleted offline
- THEN `\Deleted` is staged where the message is and the row stays, saying so

### Requirement: A message is sent through an outbox
Submitting SHALL compose the message on the device and stage it in the account's outbox, a collection no server enumerates. A sync SHALL drain it, submitting each message, filing the `\Sent` copy and dropping the outbox row; a message the submission refuses SHALL stay in the outbox and say so.

#### Scenario: Composed with no network
- GIVEN no network
- WHEN a message is sent
- THEN it is in the outbox, shown as pending, and the composer closes

#### Scenario: The next sync
- GIVEN a message in the outbox
- WHEN a sync runs
- THEN it is submitted, a copy is filed, and the outbox row goes

#### Scenario: A blind copy
- GIVEN a message naming a blind copy
- WHEN it waits in the outbox
- THEN the stored bytes carry the `Bcc` header, and the submission strips it into the envelope

## MODIFIED Requirements

### Requirement: A message's markers can be written
The app SHALL write the `\Seen`, `\Answered` and `\Flagged` markers of one message in the store, and the next sync SHALL push the difference between the staged set and the set the source last agreed on, so a keyword the app does not model is never replaced. The markers are named the IMAP way whichever backend answers, JMAP's keywords mapping onto the same three (RFC 8621 section 4.1.1).

#### Scenario: Marking a message read
- GIVEN a message the store holds without `\Seen`
- WHEN the reader marks it read
- THEN the stored placement carries `\Seen` and the list reflects it
- AND the next sync tells the server, over `UID STORE` or `Email/set`

#### Scenario: A keyword the app does not model
- GIVEN a message the server also marks `$junk`
- WHEN the reader flags it and the sync pushes
- THEN only `\Flagged` is added and `$junk` is left alone

#### Scenario: A marker JMAP has no keyword for
- GIVEN a JMAP account
- WHEN the push names `\Deleted`, which RFC 8621 gives no keyword
- THEN the push fails saying so, rather than inventing one

### Requirement: A message is deleted into the account's trash
Deleting a message SHALL stage a move into the mailbox the account records as its trash, the one carrying the RFC 6154 `\Trash` attribute over IMAP or whose role is `trash` over JMAP, and the row SHALL leave the list at once. The next sync SHALL perform the move, with MOVE or with a COPY where the server implements none. Nothing SHALL be expunged, an expunge without `UIDPLUS` being mailbox-wide and taking every message another client had marked.

Where the account records no trash, `\Deleted` SHALL be staged in place and the row SHALL stay in the list, which says so. A JMAP account recording none SHALL refuse the delete: RFC 8621 has no counterpart to `\Deleted`, so there is nothing to mark it with.

#### Scenario: An account with a trash
- GIVEN an account recording a mailbox marked `\Trash`
- WHEN a message is deleted
- THEN the row leaves the list at once
- AND the next sync moves it there

#### Scenario: A message already in the trash
- GIVEN a message whose mailbox is the trash
- WHEN it is deleted
- THEN `\Deleted` is staged where it is rather than a move into the mailbox it already sits in

### Requirement: Opening a message marks it read
Opening a message SHALL mark it read in the store, once its body has arrived, and the next sync SHALL push it. The fetch itself SHALL NOT: it asks for `BODY.PEEK[]`, so a read that failed leaves the message unread.

#### Scenario: Opening an unread message
- GIVEN an unread message
- WHEN the reader opens it and the body arrives
- THEN it is marked read in the store, and on the server by the next sync

#### Scenario: The body never arrives
- GIVEN an unread message whose fetch fails
- WHEN the reader closes it
- THEN it is still unread

### Requirement: A message is submitted and a copy is kept
A sync draining the outbox SHALL hand each message's bytes to the account's submit endpoint with the envelope its headers name, blind recipients included, then `APPEND` a copy carrying no `Bcc` into the mailbox the account records as `\Sent` (RFC 6154), already `\Seen`. The copy SHALL be filed after the submission and never instead of it. An account with no sent mailbox SHALL send anyway and say no copy was kept.

#### Scenario: A message that is sent
- GIVEN an outbox message and an account with a submit endpoint and a `\Sent` mailbox
- WHEN the sync drains the outbox
- THEN it is handed over, a copy is filed, and the outbox row goes

#### Scenario: A submission the server refuses
- GIVEN a server rejecting the envelope
- WHEN the sync drains the outbox
- THEN nothing is filed anywhere, the message stays in the outbox, and the failure is shown

### Requirement: A calendar entry can be created
The app SHALL create a calendar entry from the entry page in the store alone: a fresh object carrying the `UID`, `DTSTAMP` and `DTSTART` RFC 5545 section 3.6.1 requires and nothing else, staged as a pending create. The next sync SHALL PUT it to a resource named after its `UID`, guarded by `If-None-Match: *`.

#### Scenario: A new entry
- GIVEN a writable calendar
- WHEN the agenda's add button is used and the page is saved
- THEN the entry appears in the agenda at once, with no network
- AND the next sync creates it on the server

#### Scenario: The resource already exists
- GIVEN a server already holding that resource
- WHEN the sync pushes the create
- THEN the server refuses it, nothing is overwritten, and the create stays staged

#### Scenario: Several calendars
- GIVEN more than one writable calendar
- WHEN a new entry is started
- THEN the calendar it lands in is asked for, and taken silently when there is only one

### Requirement: A calendar entry can be deleted
The app SHALL stage a calendar entry's deletion, after asking, and the entry SHALL leave the agenda at once. The next sync SHALL delete it guarded by the ETag it was read at; a delete the server refuses SHALL leave the entry staged for the sync that follows.

#### Scenario: Deleting an entry
- GIVEN an entry open on its page
- WHEN it is deleted
- THEN it leaves the agenda at once, and the next sync tells the server

#### Scenario: The entry moved under it
- GIVEN an entry another client has edited since it was read
- WHEN the sync pushes the delete
- THEN the server refuses on the precondition and the removal stays staged

#### Scenario: An entry the server has never seen
- GIVEN a new entry that has not been synced
- WHEN it is deleted
- THEN the staged create is withdrawn and nothing is ever sent

## REMOVED Requirements

### Requirement: Mail and calendar are read-only mirrors
**Reason**: both domains now carry staged edits, bases and conflicts on their bindings, which is what running io-pimdir's sync means. A refresh reconciles a collection rather than replacing it, so a staged write survives one.
