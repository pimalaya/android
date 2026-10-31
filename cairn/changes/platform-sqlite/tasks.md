---
cairn: tasks
change: platform-sqlite
---

# Tasks

- [ ] Review with the user, with pimdir's the-sqlite-floor-is-a-recommendation and io-pimdir's an-io-free-indexer
- [ ] Waits on: pimdir storage-draft-07 and search-draft-07 (landed 2026-10-31, uncommitted); io-pimdir on draft-07 with the I/O-free refresh
- [x] 1. flake.nix: host SQLite 3.18.2 with Android 8.0's flags (dist/Android.mk); a test preparing every served statement and running every migration, failing first against today's text
  - Guard: flake.nix `sqliteFloor` (`PIMALAYA_SQLITE_FLOOR`, with nix/sqlite-floor-check.c) and `served_sql_holds_to_the_sqlite_floor` in rust/src/ffi/offline.rs, part of `cargo test`; run `nix develop --command sh -c "cd rust && cargo test --lib served_sql_holds_to_the_sqlite_floor"`; red on 2026-10-31 (migration 0001 refused at STRICT, 186 statements failing).
- [ ] 2. rust/pimdir/: the shape's migrations and queries at draft-07, `store_meta.shape`; the guard runs a list-parameter table's creation (pimdir STORAGE §4.4's `create_param`, or the shape's own) before it prepares; the draft-05 rules (cairn/changes/io-pimdir-draft-05); Java's hand-written SQL moved in; start-up blocked on an incompatible store (another shape, or one failing to open), tested over a store of today's shape; pin moved; device check on requery
- [ ] 3. Back to `android.database.sqlite` in the 15 classes; requery and its host build out; a pre-step-2 store meeting the block; API 26 emulator and device check
- [ ] 4. index.db shape (FTS4), `PimdirIndexDb`, refresh wiring, the bridge's compile; search vectors' hit sets on the 3.18.2 build; device check
- [ ] 5. cairn/changes/lists-from-the-index on the index; measure, drop the timing log
- [ ] `:app:assembleDebug :app:testDebugUnitTest :app:assembleRelease` green; delta folded into spec/offline-store.md; log; CHANGELOG
