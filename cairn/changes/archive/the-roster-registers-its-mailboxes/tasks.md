---
cairn: tasks
change: the-roster-registers-its-mailboxes
---

# Tasks

- [x] T1: regression test first, MailMoveTest: a delete into a trash the filter leaves unticked and no pass listed is staged as a move and pushed as a server relocation; it fails before the fix with `target-unsynced`.
- [x] T2: `PimdirCollections.register`, io-pimdir's `register` over the canonical `ensure_collection`, `load_checkpoint` and `upsert_checkpoint`, one owner write; `EventStore.ensureSynced` goes to it.
- [x] T3: `MailStore.replaceMailboxes` registers every mailbox the roster declares under the server source.
- [x] T4: MailMoveTest `aBodilessMoveIntoAMailboxNeverSyncedIsRefused` reads the rule as it now stands: a declared mailbox never synced takes the move; a collection no source registered still refuses it.
- [x] T5: gates: the JVM suite green, SyncVectorsTest unchanged (184 pass, 0 fail, 58 unbound), `:app:assembleDebug`, `cargo fmt`, `cargo clippy --locked --all-targets -- -D warnings`, `cargo test --locked`.
- [x] T6: delta folded into cairn/spec/offline-store.md and cairn/spec/mail.md, log entry, change archived; no CHANGELOG line (unreleased feature).
