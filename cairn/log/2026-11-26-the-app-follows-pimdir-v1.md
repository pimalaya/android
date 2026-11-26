---
cairn: log
change: the-app-follows-pimdir-v1
landed: 2026-11-26
---

# The app follows pimdir v1

The Rust bridge moves from io-pimdir 0.20.2 to 1.0.0 (pimdir storage-v1.0.0, sync-v1.0.0, search-v1.0.0), read from io-pimdir's CHANGELOG.md sections 0.20.3, 0.20.4 and 1.0.0. A dependency move, no proposal; cairn/log/2026-11-24-the-bridge-follows-the-releases-of-2026-11-24.md is the model.

A store any 0.x build wrote, every pimdir draft's, is refused (`store-version`, `storage-draft-17, earlier than this app's storage-v1`) and never migrated: testers and the owner reinstall over a new store (sync and empty the outbox first). An index of a draft is rebuilt.

## The manifest

- **Manifest**: rust/Cargo.toml requires io-pimdir `1.0.0`, the dependency (`indexer`) and the dev-dependency (`client`, `vendored`, `search`) alike, taken from crates.io with no patch.
- **Lock**: `cargo update -p io-pimdir` moved it alone, its dependencies unchanged.
- **Flake**: flake.nix fetches the spec vectors at the v1.0.0 tag.

## API adaptations

- `load::PimdirRefusal` is `PimdirBindingRefusal` (rust/src/offline.rs), `search::chunk::Chunks` is `PimdirChunks` (rust/src/search/find.rs).
- Tests only: `PimdirStore::for_source` answers a `Result`, unwrapped; `PimdirRunError` is imported from `client::run`.
- Nothing else the bridge names moved: `PimdirSearchCursor` and `PimdirSearchSort` were already read from `search::plan`, `FORMAT_DRAFT` was not read, `PimdirQueryDateRange` is only read, no match on `PimdirQueryErrorCode` or `PimdirMutateError` needs a new arm. No payload or error shape the Java half reads changed: the Java bridge is untouched but for a comment.

## What moved

- **The versions**: the store is created and checked at `storage-v1` and stamped `sync-v1` by the crate's names (`sql::FORMAT_VERSION`, `SYNC_VERSION`); the app's check (rust/src/offline/store.rs) answers an owner's opening as io-pimdir 1.0's `client::schema::check` does for a store of version 1. The fork rust/pimdir/queries/search/init_index_meta.sql writes `search-v1` under the canonical text's new digest (cf322138faded22b); `held` already ordered every draft below every release. The conformance statement reads `storage-v1.0.0 owner (own statement text), sync-v1.0.0 engine` (rust/src/offline/shape.rs).
- **The fixtures**: the version names in rust/src/offline/store.rs, rust/src/ffi/offline.rs, rust/src/offline/shape/tests.rs and rust/src/search/tests.rs (storage-draft-17 and sync-draft-15 refused, search drafts up to 19 rebuilt, storage-v2 and search-v2 refused as later), StoreRefusalTest, MailIndexTest, PimdirDbTest and PimdirStorageTest.
- **The search vectors**: queries.json moved to format 6 for a case's `matched`; the app reports no match source per hit, so the three cases stating it run their hits and leave `matched` uncompared, said in the test's output.
- **The sync vectors**: 242 cases. Six new ones are listed unbound for what the bridge never runs (224, 225, 239, 242 under a policy other than manual; 235, 241 a rekey).

## Evidence

From the repository root through the flake: `cargo fmt --check` clean; `cargo clippy --all-targets --locked -- -D warnings` clean; `cargo test --locked` 749 passed, 1 ignored (the search vectors: 348 run, 7 not binding the shape, 3 run without `matched`); `cargo deny check` advisories, bans, licenses and sources ok. The first run on the new pin failed 22 tests, the version names, the fork's digest (`left: Some("daf27bef9396135c") right: Some("cf322138faded22b")`), every search test reading a fresh index (`Rebuild { reason: "an index of search-draft-17" }`) and queries.json's format (`left: Number(6) right: 5`). `gradle -p android :app:assembleDebug`: green. `gradle -p android :app:testDebugUnitTest`: 1,075 tests, 1 failed, 3 skipped, the failure SyncVectorsTest's.

## Left

- **Ten sync vectors fail unlisted**: 114, 115, 222, 223, 227, 228, 229, 230, 232 and 233, owner fixes of io-pimdir 0.20.3 to 1.0.0 the Java owner does not follow yet (a retained body's own sort key, a revive adopting another body, two members of one `Message-ID` sharing no body, a copy or a move staged under the source syncing its target or refused as `target-unsynced`). sync-vectors.txt says its `fails` lines only go, so none was listed: cairn/changes/the-store-follows-sync-v1 holds the port, and SyncVectorsTest stays red until it lands.
- A store at `storage-v1` stating schema version 2 is refused `store-corrupt` by the app's check where io-pimdir names it `store-version`; no such store exists before a migration ships.
- `:app:assembleRelease` was not run. Nothing was installed on a device and no account was reached: the owner's check is a reinstall over a draft store, which opens on the reinstall screen, then a fresh store and index created on the phone.

## Capabilities moved

- offline-store: the schema and the index at v1 (introduction, "The store's shape follows the canonical schema", "The index is the app's own shape over FTS4"), "A store of another draft is not opened" renamed "A store of another version is not opened" with its scenarios at v1, "An incompatible store blocks start-up", the previous build's scenario, and the sync vectors' counts at 1.0.0.
- mail: the search reads SEARCH §8 at search-v1.
