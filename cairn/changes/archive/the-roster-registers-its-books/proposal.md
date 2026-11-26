---
cairn: change
id: the-roster-registers-its-books
status: landed
created: 2026-11-26
---

# The roster registers its books

## Why

Since the app follows sync-v1 (cairn/log/2026-11-26-the-store-follows-sync-v1.md), a copy or a move into a collection no source syncs is refused, `target-unsynced` (pimdir SYNC §7): a source syncs a collection when it holds a binding or a `sources` row there (`collection_sources`). The mail roster now registers every mailbox its server declares (cairn/log/2026-11-26-the-roster-registers-its-mailboxes.md), and the CalDAV calendar roster did already; the address book roster does not, so a book the server declares and no pass ever listed (one left unsubscribed, or subscribed with its server switch off) is synced by no source, and a card copied or moved into it would be refused.

The owner decided on 2026-11-26 that address books follow the same rule: the app records each book its server declares as synced by that account's server source, as io-pimdir 1.0.0's `PimdirSourceStore::register` does (STORAGE §15.6), so such a copy or move is staged, never refused `target-unsynced`.

## What

- **Where the books are read.** One bridge call, `ContactClient.listAddressbooks` (rust/src/contact.rs `list_addressbooks`), answers every backend: a CardDAV home set (rust/src/contact/carddav.rs), JMAP address books (rust/src/contact/jmap.rs), Google People contact groups (rust/src/contact/gpeople.rs) and Graph contact folders (rust/src/contact/msgraph.rs). Its answer reaches the store at three places:
  - `AccountCommit.books`, the onboarding and account settings commit, through `PimdirCollections.replace`;
  - `SyncPasses.syncRemote`, re-fetching the books of an account a schema rebuild dropped, through `PimdirCollections.replace`;
  - `SyncPasses.syncAccount`, every contacts pass, which reads the roster for its roles and writability and leaves it as it is, through `PimdirCollections.restate`.
  Other listings (the onboarding's book picker, the password check) write nothing to the store.
- **What is registered.** Every book the roster declares, subscribed or not, its server switch on or off, under the `server` source every account's books are synced by. Not the on-device book, which no server declares, nor a book's phone spoke, which is the phone source's.
- **When and how.** At each of those three reads, in the roster's own owner write: `replace` takes the source to register its listed books under, in its transaction; the pass's read restates and registers in one transaction, registering only the declared books the store holds (that read leaves the roster as it is, so a book the server added since is left to the next `replace`, never ensured as a bare row). The write is the shared `PimdirCollections.register`, io-pimdir's `register` over the canonical `ensure_collection`, `load_checkpoint`, `upsert_checkpoint` with no checkpoint where the source holds no row, a row held keeping its checkpoint. No SQL in Java and no new statement.
- **What it changes for sync: nothing listed or downloaded.** What a contacts pass syncs is the subscription and its switches (`CardStore.loadSubscribedAddressbooks`, `syncBook(url, remoteSynced)`, the phone pass's `phoneSyncedBooks`), never the `sources` table. A registered row holds no checkpoint, coverage or round, and every statement the app runs reads it as no row: the load's sync state (`load_checkpoint`, `load_round`), the per-book delta projection of the account-level backends (keyed by that checkpoint, null as before), `list_coverage` and `list_collections`; no card statement (`card_*`, `list_cards`) reads `sources`. The `sources_lacks_insert` trigger marks the live items of a newly registered book that the server binds not: a declared book never synced holds none, and where one does (a book whose server switch is off, holding phone creates), the mark is read only by the server source's own load, which runs only once the switch is on, and that first pass is a full listing that would push them anyway.
- **What it changes for a copy or a move.** The server source now syncs every declared book, so a card copied or moved into one no pass listed is staged as the server's own create, carrying its origin. A copy or move into a collection no source registered (a store written before this change, until its next pass reads the roster) is still refused, `target-unsynced`.
- **No app path copies or moves a card through SYNC §7 today.** The books dialog (`ContactWriter.applyBookState`) places a card in another book as a membership row on an account-level backend (`PimdirContacts.stageMembership`) or as a fresh create elsewhere (`PimdirContacts.copy`, a `mutateAdd`), and takes it out as a staged delete; none stages a `Mutation.copy` or `Mutation.move`, which alone meet `target-unsynced`. The rule is registered anyway, so it holds for any future path, and the regression test runs at the store level, as MailMoveTest's `aMoveIntoAMailboxNoSourceSyncsIsRefused` does.

## Out of scope

- Calendars other than CalDAV: a calendar relocation is refused by every connector.
- io-pimdir's `client` feature: the Java owner stays the store's one writer.
