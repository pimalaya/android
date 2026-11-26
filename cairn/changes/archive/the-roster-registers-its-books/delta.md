---
cairn: delta
change: the-roster-registers-its-books
---

# Delta: the roster registers its books

Capability file: cairn/spec/offline-store.md.

## ADDED Requirements

### Requirement: A roster registers every book its server declares
Each time an address book roster is read (an account's commit, the re-fetch of books a schema rebuild dropped, every contacts pass), the server source SHALL be recorded as syncing every book it declares, subscribed or not, its server switch on or off, as io-pimdir's `PimdirSourceStore::register` records one (STORAGE §15.6), in the roster's own owner write: each collection ensured, then a `sources` row with no checkpoint where the source holds none, a row held keeping its checkpoint. A pass's read, which leaves the roster as it is, SHALL register only the declared books the store holds. The on-device book and a book's phone spoke SHALL NOT be registered under the server. A registered row SHALL change nothing a pass lists or downloads: what a contacts pass syncs is the subscription and its switches, and a row with no checkpoint, coverage or round reads as no sync state at all.

#### Scenario: A book left unsubscribed
- GIVEN an account's commit, its Home book left unsubscribed
- WHEN the books are stored
- THEN Home is registered under the server source and nothing of it is listed or downloaded (AccountCommitTest `aBookCommittedUnsubscribedTakesACopy`)

#### Scenario: A store written before the rule
- GIVEN a store whose books were stored before they were registered
- WHEN a contacts pass reads the roster
- THEN every declared book the store holds is registered, a book synced before keeping its checkpoint and a book never listed holding no sync state (CardMoveTest `theRosterRegistersAndListsNothing`)

## MODIFIED Requirements

### Requirement: A copy or a move into a collection no source syncs is refused
A copy or a move SHALL stage its create under the source syncing its target, the run's own where it syncs it (a server-side copy), another otherwise (no origin), and SHALL be refused with `target-unsynced`, staging nothing, when no source syncs the target (SYNC §7). A mailbox the roster declares is synced by the server source from the roster's read on (A roster registers every mailbox its server declares), and so is a book (A roster registers every book its server declares), so a move or a copy into one no pass listed is the server's own, carrying its origin. No app path stages a card's copy or move as one today (the books dialog places a card as a membership row or a fresh create): the rule holds at the store for any that comes.

#### Scenario: A message moved into a mailbox never listed
- GIVEN an Archive the roster declares, the filter leaving it unticked and no pass having listed it
- WHEN a message is moved into it
- THEN the move is staged as the server's own create, its tombstone naming the Archive as its destination (MailMoveTest `aBodilessMoveIntoAMailboxNeverListedIsTheServersOwn`)

#### Scenario: A card copied or moved into a book never listed
- GIVEN a Home book the roster declares, unsubscribed and never listed, read by a contacts pass or an account's commit
- WHEN a card of a synced book is copied or moved into it
- THEN its create is staged in Home under the server source, a move tombstoning the card it leaves (CardMoveTest `aCardCopiedIntoABookNeverListedIsTheServersOwn` and `aCardMovedIntoABookNeverListedIsTheServersOwn`, AccountCommitTest `aBookCommittedUnsubscribedTakesACopy`)

#### Scenario: A message moved into a collection no source registered
- GIVEN a mailbox the store holds no binding nor `sources` row in, as a store written before its roster registered it
- WHEN a message is moved or copied into it
- THEN the move is refused (`target-unsynced`), the message staying where it was and nothing staged in the mailbox (MailMoveTest `aMoveIntoAMailboxNoSourceSyncsIsRefused`, vector 229)

#### Scenario: A card copied into a book no source registered
- GIVEN a book no roster declared, held by the store with no binding nor `sources` row
- WHEN a card is copied into it
- THEN the copy is refused (`target-unsynced`), nothing staged in the book (CardMoveTest `aCardCopiedIntoABookNoSourceSyncsIsRefused`)

#### Scenario: A card copied into a book another source syncs
- GIVEN a book only another source syncs
- WHEN a card is copied into it
- THEN its create is staged under that source, carrying no origin, so that source uploads the body the store holds (vectors 227, 228, 230)

## REMOVED Requirements

None.
