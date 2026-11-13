---
cairn: change
id: sync-vectors-run-through-the-java-store
status: landed
created: 2026-11-13
---

# The sync vectors run through the Java store

## Why

Line A2 of pimdir's audit of 2026-11-12 (pimdir cairn/changes/audit-2026-11-12/tasks.md, high): the app is the store's second owner, and runs none of the sync vectors of SYNC §11 through its Java load and write. The engine is io-pimdir's own, so its decisions are the crate's and pass there; what no test checked is the half the app wrote itself: what `PimdirStorage.loadCollection` hands the engine, what `PimdirStorage.applyWrites` does with what the engine hands back, and the wire between them. Line A1, a store-side rule the Java write lacked, would have been caught by two vectors.

## What

- **A runner** (android/app/src/test/java/org/pimalaya/pimdir/SyncVectorsTest.java), a host JVM test on Android 8.0's SQLite 3.18.2, over every case of the vectors the pinned io-pimdir carries.
  - *Seeded* by the standard's own statements served over the bridge (`set_collection_kind`, `store_object`, `insert_item`, `retain_item`, `insert_binding`, `supersede_item`, `add_reference`, `upsert_checkpoint`, `recompute_refcounts`, the app's 3.18 forks of them held equal by rust/src/offline/shape/tests.rs) and plain SQL for what io-pimdir's own runner writes by plain SQL (flag rows, rounds, summaries). Never through `applyWrites`: the write under test prepares none of its own input.
  - *Driven* by the bridge's own `offlineSync`, `offlineUpgrade` and `offlineMutate` code, over a driver extending the app's `Stager`: load, lookup, verify and write are the app's, and the remote is scripted from the case in Java, on the wire a connector of the app answers (pages, fetches, push results).
  - *Compared* as SYNC §11 says: pushes in order on the keys a case carries, events as a multiset, `failed`, `parked`, then each table on the columns its expected rows name, flag sets and lists as arrays, stamps and SQLite's instants as present or absent, objects by row and file.
- **A seam in the bridge**, in a debug library alone (rust/src/ffi/vector.rs, `#[cfg(debug_assertions)]`): the three verbs answering the run's events, which the app's reports only count, and the meta a scripted listing names a member by (`derive_meta`, a file's stated summary). The three verbs build their coroutine by the functions the production exports build theirs by, now shared.
- **A ledger** beside the test (sync-vectors.txt): each case not passing, `fails` or `unbound`, with why. Any departure fails the suite.
- **Two fixes** the vectors asked, each small and each seen failing first: the load states the copies it offers (`offered`), and the wire no longer reads a page handing no cursor as the last.

## Not in this change

The findings the ledger lists as `fails` (the policy's kept body, a retained row revived at meta, a create's origin, the summary a load carries): each is a change of the write or the load of its own. Binding the verbs and options the bridge does not state (rekey, heads, the engine's conflict policies, a sync without push, the meta tier, abandon).

## What a user meets

Nothing.
