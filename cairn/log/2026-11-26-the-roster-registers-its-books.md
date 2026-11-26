---
cairn: log
change: the-roster-registers-its-books
landed: 2026-11-26
---

# The roster registers its books

Owner decision 2026-11-26, following cairn/log/2026-11-26-the-roster-registers-its-mailboxes.md: address books follow the mailboxes' rule, so a card copied or moved into a declared book its source never listed is staged, not refused `target-unsynced` (SYNC §7).

## What landed

- android/app/src/main/java/org/pimalaya/pimdir/PimdirCollections.java: `replace` gains an overload taking the source to register every listed collection under (`register`, io-pimdir's `PimdirSourceStore::register`, STORAGE §15.6) in its own transaction; the three-argument form registers none. `restate` gains an overload for a roster read that leaves the roster as it is: it registers the declared collections the account holds of a kind and restates their roles in one transaction, the read-only set after, as before. A declared collection the store does not hold is left to the next `replace`, never ensured as a bare row. No SQL and no new statement.
- android/app/src/main/java/org/pimalaya/setup/AccountCommit.java `books` (an account's commit) and android/app/src/main/java/org/pimalaya/sync/SyncPasses.java `syncRemote` (the re-fetch of books a schema rebuild dropped) replace the roster under the `server` source; `SyncPasses.syncAccount` (every contacts pass) restates and registers.

Every backend's books come through one bridge call, `ContactClient.listAddressbooks` (rust/src/contact.rs over carddav.rs, jmap.rs, gpeople.rs and msgraph.rs: a CardDAV home set, JMAP address books, People contact groups, Graph contact folders), so the three store writes cover them all. The onboarding's book picker and the password check list books without writing the store. The on-device book and the phone spokes are never registered under the server.

## What it changes for sync

Nothing listed or downloaded: a contacts pass syncs the subscribed books by their switches (`loadSubscribedAddressbooks`, `syncBook(url, remoteSynced)`, `phoneSyncedBooks`), never the `sources` table, and a row with no checkpoint, coverage or round reads as no row in the load's `load_checkpoint` and `load_round`, in the account-level backends' per-book delta projection, in `list_coverage` and `list_collections`; no card statement reads `sources`. The `sources_lacks_insert` trigger marks the live items of a new row that the server binds not: a never-synced declared book holds none, and a book whose server switch is off is read for them only by the server's own load, which runs once the switch is on and whose first pass is a full listing.

No app path copies or moves a card through SYNC §7 today: the books dialog (`ContactWriter.applyBookState`) places a card as a membership row on an account-level backend (`PimdirContacts.stageMembership`) or a fresh create elsewhere (`PimdirContacts.copy`, a `mutateAdd`). The rule is registered for any future path and tested at the store.

## Tests

- CardMoveTest (new): a store whose books were stored before this change, Work synced and Home unsubscribed, one contacts pass reading the roster through a fake `listAddressbooks`, then `ContactStager.mutateCopy` / `mutateMove` from Work into Home. `aCardCopiedIntoABookNeverListedIsTheServersOwn` and `aCardMovedIntoABookNeverListedIsTheServersOwn` failed before the fix with `Pimdir MUTATE failed: no source syncs https://dav.example.org/books/home/, where a copy or a move stages its create`; after it the create is live in Home, the move's source a tombstone. `theRosterRegistersAndListsNothing` pins Home holding no sync state and Work keeping its checkpoint; `aCardCopiedIntoABookNoSourceSyncsIsRefused` pins a book no roster declared still refusing (vector 229).
- AccountCommitTest `aBookCommittedUnsubscribedTakesACopy` (new): a book committed unsubscribed takes a copy; it failed before the fix with the same refusal for https://dav.example.org/home/.

## Evidence

Through the flake: `gradle -p android :app:testDebugUnitTest :app:assembleDebug` green, 1,082 tests, 0 failed, 3 skipped; SyncVectorsTest 242 sync vectors, 184 passed, 0 failed, 58 unbound. rust/ untouched. Nothing was installed on a device and no account was reached.

## Changelog

None: the app is unreleased and this fixes an unreleased feature.

## Capabilities moved

- offline-store: "A roster registers every book its server declares" added; "A copy or a move into a collection no source syncs is refused" restated to name books, with the scenarios "A card copied or moved into a book never listed" and "A card copied into a book no source registered".
