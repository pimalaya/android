---
cairn: delta
change: mail-write-path
---

## ADDED Requirements

### Requirement: A message's markers can be written
The app SHALL write the `\Seen`, `\Answered` and `\Flagged` markers of one message on its server, and SHALL apply the same change to the stored envelope in the same pass so the list reflects it before the next sync. The markers are named the IMAP way whichever backend answers, JMAP's keywords mapping onto the same three (RFC 8621 section 4.1.1).

#### Scenario: Marking a message read
- GIVEN a message the store holds without `\Seen`
- WHEN the reader marks it read
- THEN the server is told, over `UID STORE` or `Email/set`
- AND the stored envelope carries `\Seen`

#### Scenario: The server refuses
- GIVEN a server rejecting the write
- WHEN the reader marks a message read
- THEN the stored envelope is left as it was, and the failure is shown

#### Scenario: A marker JMAP has no keyword for
- GIVEN a JMAP account
- WHEN a write names `\Deleted`, which RFC 8621 gives no keyword
- THEN the write fails saying so, rather than inventing one

### Requirement: Opening a message marks it read
Opening a message SHALL mark it read, once its body has arrived. The fetch itself SHALL NOT: it asks for `BODY.PEEK[]`, so a read that failed leaves the message unread.

#### Scenario: Opening an unread message
- GIVEN an unread message
- WHEN the reader opens it and the body arrives
- THEN it is marked read on the server and in the store

#### Scenario: The body never arrives
- GIVEN an unread message whose fetch fails
- WHEN the reader closes it
- THEN it is still unread

### Requirement: A message is deleted into the account's trash
Deleting a message SHALL move it into the mailbox the account names as its trash: the one carrying the RFC 6154 `\Trash` attribute over IMAP, the one whose role is `trash` over JMAP. Nothing SHALL be expunged, an expunge without `UIDPLUS` being mailbox-wide and taking every message another client had marked.

Where an IMAP account names no trash, the message SHALL be marked `\Deleted` in place and SHALL stay in the list, which says so. A JMAP account naming none SHALL fail: RFC 8621 has no counterpart to `\Deleted`, so there is nothing to mark it with.

#### Scenario: An account with a trash
- GIVEN a server marking a mailbox `\Trash`
- WHEN a message is deleted
- THEN it is moved there, with MOVE or with a COPY where the server implements no MOVE
- AND it leaves the list

#### Scenario: An IMAP account with no trash
- GIVEN a server marking no mailbox
- WHEN a message is deleted
- THEN it is marked `\Deleted` where it is, the reader is told so, and the row stays
