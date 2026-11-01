---
cairn: log
change: platform-sqlite
date: 2026-11-01
---

# Step 5: the lists on the index

cairn/changes/archive/lists-from-the-index, built on the index step 4 built: the agenda reads the index's occurrence table, Tasks and Contacts the store's summaries, Contacts' Has and categories the index, and no list reads a body to draw. What landed, the measures and the tests are in cairn/log/2026-11-01-lists-from-the-index.md. The `agenda read` and `agenda drawn` timing logs are dropped. The change stays in flight (cairn/changes/archive/platform-sqlite/tasks.md).

Capabilities moved: calendar, tasks and offline-store (cairn/changes/archive/lists-from-the-index/delta.md), offline-store's index requirement (cairn/changes/archive/platform-sqlite/delta.md, the fresh-install scenario); folded at landing, not yet.

## The index on a fresh install

Found on the API 26 emulator on a fresh install of 4cba857: `App.pimdir()` starts the index and asks a refresh at once, and the refresh's thread attached pimdir.db before anything had opened the store's helper, which creates the schema on its first open, so the first refresh failed (`no such table: store.store_meta`, while compiling `init_index_meta`) and index.db got its `index_meta` on the second launch only. `PimdirIndexDb` now opens the store's writable handle before it attaches pimdir.db, whoever asks the index first, on every API.

- Test: `PimdirIndexDbTest.aFreshStoreIsCreatedBeforeItsIndexReadsIt` starts the index of a store no one opened and refreshes it; its setUp no longer opens the store, so the class's two other tests run on a fresh store too. Failing first against 4cba857's `PimdirIndexDb`: all three, `no such table: store.store_meta`.

## Verified

Verified on 2026-11-01: `cargo test` 603 passed (the ignored one the weight measure), `cargo fmt` and `cargo clippy --all-targets -- -D warnings` clean; `gradle :app:assembleDebug :app:testDebugUnitTest :client:testDebugUnitTest :app:assembleRelease` green (856 app tests, one skipped: `AgendaMeasure`, run by hand; 14 client), cairn/verify.sh conformant. Release arm64-v8a 11,105,970 to 11,170,054 bytes. No emulator nor device touched: the device check is owed.

## Device check (2026-11-01)

OnePlus 5, Android 15, installed over the store (the Fastmail test account), and an API 26 emulator (Android 8.0, SQLite 3.18.2) over its own fresh store: both started clean, no `no such table`, no `search index` warning, no `SQLiteException`. On the phone the first refresh recorded the device's zone (`index_meta.floating_zone` `Europe/Paris`) and the four test events' occurrences; the agenda listed no upcoming entry, those four being past (August to October 2026), as it should. Contacts listed from the summaries: names, the email as second line, origins.

Decided with the user: a contact row's second line is the email, else the organization, read from the summaries; no phone column is added to pimdir's summaries (it would change the canonical schema and reinstall every draft-08 store again). The offline-store delta states it.
