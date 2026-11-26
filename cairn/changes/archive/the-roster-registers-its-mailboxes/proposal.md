---
cairn: change
id: the-roster-registers-its-mailboxes
status: landed
created: 2026-11-26
---

# The roster registers its mailboxes

## Why

Since the app follows sync-v1 (cairn/log/2026-11-26-the-store-follows-sync-v1.md), a copy or a move into a collection no source syncs is refused, `target-unsynced` (pimdir SYNC §7): a source syncs a collection when it holds a binding or a `sources` row there (`collection_sources`). Deleting a message moves it into the account's trash (MailStager `stageDelete`), and the trash is unticked by default (the filter's first roster quiets it), so no pass ever lists it and no `sources` row is written there: every delete of an account whose trash was never synced is refused. A move or a copy into any other mailbox never synced (an Archive left unticked) is refused the same way.

The owner decided on 2026-11-26 that the app records each mailbox its server declares as synced by that account's source, as io-pimdir 1.0.0's `PimdirSourceStore::register` does (STORAGE §15.6), so such a delete is staged under the server's source and pushed.

## What

- **What is registered.** Every mailbox the server's roster declares, ticked or not: the mailboxes `MailStore.replaceMailboxes` receives, under the `server` source every account's mailboxes are synced by. Not Gmail's hidden All Mail, which the app makes and the server declares no mailbox for, nor the outbox, a queue collection.
- **When.** Each time the roster is read (`replaceMailboxes`: every full mail pass and an account's first step), right after the roster's replace, in one owner write.
- **How.** As `PimdirSourceStore::register` does: each collection ensured (`ensure_collection`, a no-op for a collection the roster just wrote), then a `sources` row with no checkpoint where the source holds none (`load_checkpoint`, then `upsert_checkpoint` with a NULL checkpoint), a row held keeping its checkpoint. The app is built without io-pimdir's `client`, its owner being the Java store over the canonical statements the bridge serves by name, so the write runs those three statements in that order; it is the write `EventStore.ensureSynced` already makes for each CalDAV calendar listed (cairn/spec/tasks.md), which moves to the one shared helper, `PimdirCollections.register`.
- **What it changes for sync: nothing listed.** What a pass syncs is the filter's choice (`SyncPasses.fetchMail` skips what the scope hides), never the `sources` table. A registered row holds no checkpoint, no coverage and no round, and every statement the app runs reads it as it reads no row: the load's sync state (`load_checkpoint`, `load_round`: no checkpoint, no coverage, no round), `list_coverage` and `list_collections` (no closed coverage), the search's coverage (a mail scope names the server source, and a mailbox it holds no row for already counts as never covered), `release_before` (a mailbox has the one source, the one binding its members), `list_lacking_items` (the `lacks` mark the `sources` triggers keep stands for the walk). The trash so downloads nothing because it is registered: it is listed only once ticked, or under its chip's pull, as before, its first pass a full listing.
- **What it changes for a move.** The server source now syncs every declared mailbox, so a delete into an unticked trash, and a move or a copy into any declared mailbox, is staged as the server's own create carrying its origin (a server move, a server copy), the bodiless case included, and pushed by the next sync of the mailbox it leaves. The create is recorded delivered as the relocation leaves (SYNC §2) and is bound by the trash's first listing, whenever it is ticked. A move into a collection no source registered (a store written before this change, until its next roster) is still refused, `target-unsynced`.

## Out of scope

- Address books and calendars other than CalDAV: a card copied into a book is the contacts engine's, and a calendar relocation is refused by every connector.
- io-pimdir's `client` feature: the Java owner stays the store's one writer (cairn/spec/offline-store.md, "The store's writer opens first").
