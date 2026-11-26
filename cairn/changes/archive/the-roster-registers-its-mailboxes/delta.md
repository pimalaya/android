---
cairn: delta
change: the-roster-registers-its-mailboxes
---

# Delta: the roster registers its mailboxes

Capability files: cairn/spec/offline-store.md, cairn/spec/mail.md.

## ADDED Requirements

### Requirement: A roster registers every mailbox its server declares (offline-store)
Each time a mail roster is read, the server source SHALL be recorded as syncing every mailbox it declares, ticked or not, as io-pimdir's `PimdirSourceStore::register` records one (STORAGE §15.6): in one owner write, each collection ensured, then a `sources` row with no checkpoint where the source holds none, a row held keeping its checkpoint. Gmail's All Mail and the outbox, which the server declares no mailbox for, SHALL NOT be registered. A registered row SHALL change nothing a pass lists or downloads: what a pass syncs is the filter's, and a row with no checkpoint, coverage or round reads as no sync state at all, so a registered mailbox's first pass, once ticked or pulled under its chip, is a full listing.

#### Scenario: A trash the filter leaves unticked
- GIVEN an account's first roster, its trash quieted by the filter
- WHEN a pass runs
- THEN the trash is registered under the server source and nothing of it is listed or downloaded

#### Scenario: A mailbox synced before
- GIVEN an inbox whose last round left a checkpoint
- WHEN the roster is read again
- THEN the inbox keeps its checkpoint, its next pass a delta

## MODIFIED Requirements

### Requirement: A copy or a move into a collection no source syncs is refused (offline-store)
A copy or a move SHALL stage its create under the source syncing its target, the run's own where it syncs it (a server-side copy), another otherwise (no origin), and SHALL be refused with `target-unsynced`, staging nothing, when no source syncs the target (SYNC §7). A mailbox the roster declares is synced by the server source from the roster's read on (A roster registers every mailbox its server declares), so a move or a copy into one no pass listed is the server's own, carrying its origin.

#### Scenario: A message moved into a mailbox never listed
- GIVEN an Archive the roster declares, the filter leaving it unticked and no pass having listed it
- WHEN a message is moved into it
- THEN the move is staged as the server's own create, its tombstone naming the Archive as its destination (MailMoveTest `aBodilessMoveIntoAMailboxNeverListedIsTheServersOwn`)

#### Scenario: A message moved into a collection no source registered
- GIVEN a mailbox the store holds no binding nor `sources` row in, as a store written before its roster registered it
- WHEN a message is moved or copied into it
- THEN the move is refused (`target-unsynced`), the message staying where it was and nothing staged in the mailbox (MailMoveTest `aMoveIntoAMailboxNoSourceSyncsIsRefused`, vector 229)

#### Scenario: A card copied into a book another source syncs
- GIVEN a book only another source syncs
- WHEN a card is copied into it
- THEN its create is staged under that source, carrying no origin, so that source uploads the body the store holds (vectors 227, 228, 230)

### Requirement: A message is deleted into the account's trash (mail)
Gains one scenario, the requirement's text unchanged:

#### Scenario: A trash the filter leaves unticked
- GIVEN an IMAP account whose trash is unticked and was never listed
- WHEN a message is deleted from the inbox
- THEN it shows in the trash at once, and the next sync of the inbox moves it into the trash on the server, the trash still not listed (MailMoveTest `aDeleteIntoATrashNeverListedIsStagedAndPushed`)

## REMOVED Requirements

None.
