---
cairn: log
change: the-app-follows-neverest-v1
date: 2026-11-18
---

# The app follows io-pimdir 0.16 (phase B)

Phase B of cairn/changes/the-app-follows-neverest-v1: io-pimdir 0.15.1 to 0.16.0 (released 2026-11-17), the dependency and the dev-dependency of rust/Cargo.toml. `cargo update -p io-pimdir --precise 0.16.0` moved io-pimdir alone in rust/Cargo.lock.

## What did not move

The format stays storage-draft-13, sync-draft-13 and search-draft-13: io-pimdir's `sql::FORMAT_VERSION`, `sync::SYNC_VERSION` and `sql::search::FORMAT_DRAFT` (13) are unchanged and its vendored spec/ tree is the same object at v0.15.1 and v0.16.0 (REVISION 1c6442e). A store this app wrote at 0.15.1 opens as it was. No canonical statement text changed, so no fork the app keeps was re-read; no `PimdirWriteOp` shape changed, so rust/src/offline/wire.rs is untouched. No production line of the app changed.

## What reaches the app

- **Through the engine or the refresh, free:** a VTIMEZONE or a recurrence rule with a huge `INTERVAL` no longer aborts a calendar summary nor the search expansion; an HTML comment closed by three dashes or more no longer swallows the rest of a body from the index; a listing at the base revision no longer replaces the summary and sort key of a contact or entry edited on the phone (the Java load sends no summary, so the old engine wrote the listing's). The last one is sync vector 58, which the app now passes.
- **The index is rebuilt once:** io-pimdir's `EXTRACTOR` is `io-pimdir 2`, so `index_meta.extractor` reads `pimalaya-android 1, io-pimdir 2` (rust/src/search/shape.rs) and the phone rebuilds index.db at its first start, as offline-store "A store whose index moved" says.
- **New API, unused:** the search cursor (`PimdirSearchOptions::after`) is in the `client` feature, which the app does not take; its own offset paging (rust/src/search/find.rs) keeps the defect 0.16 fixed upstream, left as task L5. `PimdirHub::immutable` stays false (rust/src/offline/merge.rs builds the hub from `Default` and merges mutable kinds alone). `PimdirMutateError::MoveInPlace` would cross as `refused: "move-in-place"`; the UI stages no move into the message's own mailbox (MessageView's picker lists the other mailboxes alone).
- **Not reached:** the rekey fixes (the bridge runs no rekey; the rekey vectors are listed unbound), a store on a rollback journal (the store is WAL), a reader reading a sync's commit half way (each app read is one statement or one transaction), and every `pimdir` command-line fix.

## Tests

- SyncVectorsTest: case 58 (`a-local-file-edit-is-pushed`) passes and the runner failed on its listing, as it must; its `fails` line is gone from android/app/src/test/resources/org/pimalaya/pimdir/sync-vectors.txt. 167 cases: 126 pass, 10 fail, 31 unbound.
- MailPoolTest `aFreeWorkerTakesTheNextKeyWhileASlowOneRuns` failed once in the full run (`[a, slow, c]` for `[a, c, slow]`): the key `c` counts the latch down before its worker records it, so `slow` may land first. The test now compares the landed keys sorted; what it proves (the slow key lands only once the other worker took the rest) is unchanged. Not caused by the bump: nothing in `WorkerPool` reaches io-pimdir.

## Evidence

From the repository root, `PIMALAYA_IO_PIMDIR_SPEC` at io-pimdir v0.16.0's spec/ (`git archive v0.16.0 spec` into the scratchpad), on 2026-11-18:

- `cargo fmt --check`, `cargo clippy --all-targets --locked -- -D warnings` in rust/: clean.
- `cargo test --locked` in rust/: 686 passed, 1 ignored (as at 0.15.1).
- `gradle -p android :app:testDebugUnitTest :app:assembleDebug --console=plain`: 1,022 tests, 5 skipped, 2 failing (the two above); after the two test changes, SyncVectorsTest and MailPoolTest re-run green. The full suite is run again with phase I.

Nothing was installed on a device and nothing connected to an account.

## Capabilities moved

- offline-store (cairn/spec/offline-store.md): modified "The sync vectors run through the app's load and write" (the pin and the counts).
