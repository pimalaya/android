---
cairn: delta
change: imap-link-id-per-account
---

Folds into `spec/mail.md` (the link id) and `spec/offline-store.md` (Deleted items).

## ADDED Requirements

## MODIFIED Requirements

### Requirement: A link id names a message across mailboxes only where the account lists them as one
In `spec/mail.md`. A message's link id SHALL name one message in its account: on Gmail, JMAP and Graph its handle, on IMAP its UID, its mailbox's UIDVALIDITY and its mailbox, written `<UID>:<UIDVALIDITY>:<mailbox>`, the mailbox last so the three read apart whatever it holds (`/`, `#`, `:`), never beginning with a prefix pimdir reserves (STORAGE §9). An IMAP message's handle SHALL stay its UID within its mailbox, and every server verb on it (a body fetch, the reader's fetch, a marker, a move, a copy, a delete) SHALL name that UID. A message SHALL be filed under the link id its listing names it by: a pending create landed onto an arrival (a sent copy, a copy, an append) SHALL take the arrival's, and a bound handle SHALL keep its own. Only on an account listed account-wide (Gmail, JMAP), whose id names one message across its mailboxes, SHALL a body be linked from another mailbox under the same link id, and only from the same account (pimdir STORAGE §14). On IMAP a link id names a message in its own mailbox alone, and on Graph a folder's copy alone: no body SHALL be linked there, every body being fetched. Once per such account, every body its mailboxes hold under a link id another collection holds with the same body SHALL be released back to its summary, both sides, unless the message still needs it (a conflict, a pending create, a local edit), so the body step reads it again.

A pending create's origin, the binding its push copies from (pimdir SYNC §3), SHALL be one of the same account holding the body the create carries, when it carries one, and, for mail, the create's `Message-ID`: an IMAP copy SHALL come from the mailbox holding that message, whatever other mailbox reuses its UID.

#### Scenario: Two IMAP mailboxes holding the same UIDs
- GIVEN an IMAP account whose inbox and archive both hold UIDs 1 to 120
- WHEN a step reads the bodies of UID 120 in both
- THEN each holds its own message's body, both read from the server

#### Scenario: Two IMAP mailboxes reusing a UID, each attaching a file
- GIVEN an IMAP account, UIDVALIDITY 7, whose inbox's UID 42 attaches invoice.pdf and whose archive's UID 42 attaches beach.jpg
- WHEN both bodies are read
- THEN the two are filed as `42:7:INBOX` and `42:7:Archive`, read from the server as UID 42, and each lists its own file alone

#### Scenario: A sent copy filed in an IMAP mailbox
- GIVEN a sent message's copy staged in an IMAP account's Sent
- WHEN Sent's listing names it as UID 900 under UIDVALIDITY 7
- THEN the copy lands on it as one message filed as `900:7:Sent`, bound to UID 900, nothing appended

#### Scenario: A store upgraded over wrongly linked bodies
- GIVEN an IMAP account whose sent UID 120 holds the inbox UID 120's body
- WHEN its next pass lists its mailboxes
- THEN both bodies are released, and the body step reads each message's own

#### Scenario: A copy from a mailbox whose UID another reuses
- GIVEN an IMAP account whose inbox and archive both hold a UID 42, two different messages
- WHEN the archive's 42 is copied into Sent
- THEN the sync copies the archive's 42, not the inbox's

### Requirement: Deleted items can be restored or purged
In `spec/offline-store.md`. A drawer row SHALL open Deleted items: every item the store holds deleted, across mail, contacts and calendars, each with its summary, domain, account, last collection and deletion time, the items a source still binds first, then newest deletion first, shown 100 at a time. The app runs io-pimdir without `client`, so the list SHALL be read through pimdir's canonical `list_retained` statement, one collection a keyset page at a time, and ordered in memory. An item a source still binds SHALL say it is waiting for the server and offer no action. An item held as summary only SHALL say it is not stored on the device. An item whose body the store holds SHALL be restorable into a writable collection of its kind and account the user picks, its last one preselected (else the account's default), as a local creation of the stored body, byte for byte, the next sync uploads: back into its last collection it revives the retained row and its public id (STORAGE §11.1), elsewhere it is a new placement, a contact or event keeping its UID, a mail named from its body. A restore over an item the target holds live SHALL be refused. Mail SHALL be restorable on IMAP only, where the push appends a create with no origin, the restored message taking a new UID once uploaded and the link id its mailbox's listing names it by; Graph, Gmail and JMAP SHALL refuse it, saying it is not supported for this account yet. The page SHALL show the space a purge releases and, on confirmation, purge every item retained until then and collect the bodies nothing references any more (`purge_retained_before`, `collect_garbage`), leaving the waiting items. No orphan blob walk SHALL run, since the app's writers take no io-pimdir staging lock. The Free space button SHALL carry the size the purge reclaims, as the summary line does.

#### Scenario: A contact deleted by mistake
- GIVEN a contact deleted and synced
- WHEN it is restored from Deleted items into its address book
- THEN it shows in the address book at once, and the next sync uploads it

#### Scenario: A mail never opened
- GIVEN a deleted mail whose body was never fetched
- WHEN Deleted items lists it
- THEN it shows, says it is not stored on the device, and offers no restore

#### Scenario: A mail in Latin-1
- GIVEN a deleted IMAP message opened once, its body in an 8-bit charset
- WHEN it is restored into its mailbox
- THEN the stored body is the bytes the server sent, and the next sync appends them

#### Scenario: Free space
- GIVEN retained items and one waiting for the server
- WHEN Free space is confirmed
- THEN the retained items and their bodies go for good, and the waiting one stays

## REMOVED Requirements
