---
cairn: delta
change: deleted-items
---

## ADDED Requirements

### Requirement: Deleted items can be restored or purged
A drawer row SHALL open Deleted items: every item the store holds deleted, across mail, contacts and calendars, each with its summary, domain, account, last collection and deletion time, the items a source still binds first, then newest deletion first, shown 100 at a time. The app runs io-pimdir without `client`, so the list SHALL be read through pimdir's canonical `list_retained` statement, one collection a keyset page at a time, and ordered in memory. An item a source still binds SHALL say it is waiting for the server and offer no action. An item held as summary only SHALL say it is not stored on the device. An item whose body the store holds SHALL be restorable into a writable collection of its kind and account the user picks, its last one preselected (else the account's default), as a local creation of the stored body, byte for byte, the next sync uploads: back into its last collection it revives the retained row and its public id (STORAGE §11.1), elsewhere it is a new placement, a contact or event keeping its UID, a mail named from its body. A restore over an item the target holds live SHALL be refused. Mail SHALL be restorable on IMAP only, where the push appends a create with no origin, the restored message taking a new UID once uploaded, its link id being the handle; Graph, Gmail and JMAP SHALL refuse it, saying it is not supported for this account yet. The page SHALL show the space a purge releases and, on confirmation, purge every item retained until then and collect the bodies nothing references any more (`purge_retained_before`, `collect_garbage`), leaving the waiting items. No orphan blob walk SHALL run, since the app's writers take no io-pimdir staging lock.

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

## MODIFIED Requirements

## REMOVED Requirements
