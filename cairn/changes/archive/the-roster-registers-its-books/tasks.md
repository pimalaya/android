---
cairn: tasks
change: the-roster-registers-its-books
---

# Tasks

- [x] T1: regression tests first: a card copied or moved into a declared book no pass listed, after the pass's roster read (SyncPasses) and after the onboarding commit (AccountCommit), is staged; each fails before the fix with `target-unsynced`. A sibling pins that a book no roster declared still refuses it.
- [x] T2: `PimdirCollections.replace` takes the source its listed collections are registered under, in its transaction; a pass's read restates and registers the declared books the store holds, in one transaction.
- [x] T3: `AccountCommit.books` and the recovery in `SyncPasses.syncRemote` register under the server source; `SyncPasses.syncAccount` restates and registers.
- [x] T4: gates: the JVM suite green, SyncVectorsTest unchanged (184 pass, 0 fail, 58 unbound), `:app:assembleDebug`; rust/ untouched.
- [x] T5: delta folded into cairn/spec/offline-store.md, log entry, change archived; no CHANGELOG line (unreleased feature).
