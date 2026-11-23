---
cairn: change
change: follows-storage-draft-17
---

# What the Android app needs from io-pimdir

For the io-pimdir lane (io-pimdir cairn/changes/what-the-consumers-need), read at 7f16b27. Evidence in inventory.md; logs under ~/code/pimalaya/.pimdir-migration/android/logs/.

## N1. `sql::search` without `client` (blocks the release build)

- What: compile `io_pimdir::sql::search` (statement texts, `FORMAT_VERSION`, `MIGRATIONS`, `all()`) under `indexer` too, for instance `#[cfg(any(feature = "search", feature = "indexer"))]` at src/sql.rs:18. It holds text and constants alone.
- Why: since dc19b8d `search` implies `client`, so rusqlite. The app's library takes io-pimdir without `client` (offline-store spec: no second SQLite, the platform's own). Its index refresh needs `indexer` (src/search/refresh.rs:22 `search::refresh`), and its index shape reads `sql::search` (src/search/shape.rs:18, src/search/held.rs:22).
- Evidence, measured 2026-11-23 on the host:
  - `features = ["indexer"]`: E0432/E0433, `sql::search` unresolved (log src-cargo-build-indexer).
  - `features = ["indexer", "search"]`: `rust-lld: error: unable to find library -lsqlite3` (log src-cargo-build-spike1). The NDK has no libsqlite3 either.
- App test: `cargo build` of rust/ with `features = ["indexer"]` links, and cargo-ndk arm64-v8a links.
- Workaround meanwhile: none clean. The fallback is to fork the 44 canonical index statements the bridge binds into rust/pimdir/queries/search/, with an app constant for the format name, each checked against io-pimdir in tests through the dev-dependency taking `search`. That costs 44 forks and is dropped once N1 is released. The scratch build used `vendored` to get past the link, which ships a second SQLite and is never acceptable.

## N2. Two statements removed while the app binds them (no action asked)

- `DELIVER_BINDING` (PimdirStorage.java:2070, :2635) and `LIST_GARBAGE_OBJECTS` (DeletedItems.java:379, PimdirItems.java:451) went in 46d851f as "nothing ran". The Java store runs both.
- Default: the app takes them as statements of its own under rust/pimdir/queries/app/, from their 0.19.1 text joined by object id. Restoring them in io-pimdir is not needed. This item exists so the removal list in pimdir's consumers.md is corrected: it missed both.

## N3. `DERIVATION` public (audit A5, low)

- `client::schema::DERIVATION` is `pub(crate)` (src/client/schema.rs:26). The app stamps a hand copy, 3 (rust/src/offline/store.rs:31). It still agrees at 7f16b27.
- Ask: export it outside `client`.
- Workaround: an app test creates a store through the dev-dependency's `PimdirStore` and compares `store_meta.derivation` with the app's constant.

## N4. A floor plan check of io-pimdir's own statements (owed by io-pimdir's 2026-11-22 log)

- The app runs 78 canonical statements unforked, among them the list and count readers. Their query plans on SQLite 3.18 are guarded only by the app's StorePerformanceTest (`aKeysetPageSeeksTheSortKeyItReadsBelow` and its neighbours).
- Ask: keep a 3.18 plan check in io-pimdir, or say none will exist. The app keeps its own asserts either way.

## Known defects of §3, as they reach the app (read from code, unverified at run time)

- Rekey R0 to R8: not reached. The app runs no rekey (39 unbound vectors list 11 rekey cases).
- Drain K4: not reached. The app's queue carries submission intents alone (PimdirQueue.java:24-31), and a move goes through `store.mutate`'s coroutine, which joins.
- To recheck once fixes land: vectors added for R1, R4 and K4 enter SyncVectorsTest's ledger as `unbound` (rekey, drain) unless a mutate form binds them.
