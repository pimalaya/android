---
cairn: log
change: the-roster-registers-its-mailboxes
landed: 2026-11-26
---

# The roster registers its mailboxes

Owner decision 2026-11-26, closing the "Left" line of cairn/log/2026-11-26-the-store-follows-sync-v1.md: a delete into a trash no pass ever listed (the filter quiets it on an account's first roster) was refused, `target-unsynced` (SYNC §7), and so was a move or a copy into any mailbox never listed.

## What landed

- android/app/src/main/java/org/pimalaya/pimdir/PimdirCollections.java `register`: io-pimdir 1.0.0's `PimdirSourceStore::register` (STORAGE §15.6) over the canonical statements it runs, `ensure_collection`, `load_checkpoint`, then `upsert_checkpoint` with no checkpoint where the source holds no row, one write nested in the caller's transaction. The app has no io-pimdir `client` (its owner is the Java store over the statements the bridge serves by name), so the helper runs that same sequence; no SQL and no new statement.
- android/app/src/main/java/org/pimalaya/mail/MailStore.java `replaceMailboxes`: every mailbox the roster declares, ticked or not, registered under the `server` source on each roster read (every full mail pass, an account's first step). Gmail's All Mail and the outbox are not mailboxes the server declares and are not registered.
- android/app/src/main/java/org/pimalaya/calendar/EventStore.java: its private `ensureSynced`, the same write for each CalDAV calendar listed, goes to the shared helper.

## What it changes for sync

Nothing listed or downloaded: what a pass syncs is the filter's (`SyncPasses.fetchMail` scope), never the `sources` table, and a row with no checkpoint, coverage or round reads as no row in every statement the app runs (the load's `load_checkpoint` and `load_round`, `list_coverage`, `list_collections`, the mail search's coverage, `release_before`, `list_lacking_items`). A move or a copy into a declared mailbox is now the server's own create carrying its origin, pushed as a server relocation or copy by the next sync of the mailbox it leaves. On IMAP with no `COPYUID` the trash's create is withdrawn after the relocation, as for a ticked trash, so the message leaves the phone until the trash is ticked and listed.

## Tests

- MailMoveTest `aDeleteIntoATrashNeverListedIsStagedAndPushed` (new, regression): before the fix it failed with `Pimdir MUTATE failed: no source syncs <account>/Trash, where a copy or a move stages its create`; after it the delete stages a move and the inbox's sync pushes `relocate INBOX 9 Trash`, the trash still holding no checkpoint.
- MailMoveTest `aBodilessMoveIntoAMailboxNeverSyncedIsRefused` became `aBodilessMoveIntoAMailboxNeverListedIsTheServersOwn` (the declared Archive takes the move, its tombstone naming it; failed before the fix with the same refusal) and `aMoveIntoAMailboxNoSourceSyncsIsRefused` (a collection no roster declared, made by `PimdirCollections.ensure`, still refuses it, vector 229).

## Evidence

Through the flake: `gradle -p android :app:testDebugUnitTest :app:assembleDebug` green, 1,077 tests, 0 failed, 3 skipped; SyncVectorsTest 242 sync vectors, 184 passed, 0 failed, 58 unbound. In rust/ (unchanged): `cargo fmt` no diff, `cargo clippy --locked --all-targets -- -D warnings` clean, `cargo test --locked` 749 passed, 1 ignored. Nothing was installed on a device and no account was reached.

## Changelog

None: the app is unreleased and this fixes an unreleased feature.

## Capabilities moved

- offline-store: "A roster registers every mailbox its server declares" added; "A copy or a move into a collection no source syncs is refused" restated, its never-synced scenario split into a declared mailbox taking the move and a collection no source registered refusing it.
- mail: "A message is deleted into the account's trash" gains the scenario "A trash the filter leaves unticked".
