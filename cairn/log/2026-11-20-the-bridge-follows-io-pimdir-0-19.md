---
cairn: log
change: the-bridge-follows-io-pimdir-0-19
date: 2026-11-20
---

# The bridge follows io-pimdir 0.19

The Rust bridge moves onto io-pimdir 0.19.0 (pimdir storage-draft-15, sync-draft-15, search-draft-15), ical-rs 0.7.3, vcard-rs 0.6.1 and mime-rs 0.2.1. A dependency move, no proposal; cairn/log/2026-11-19-the-app-follows-the-releases-of-2026-11-19.md is the model. The Java half was not touched.

## The manifest

rust/Cargo.toml asks io-pimdir 0.19.0 (the dependency and the dev-dependency), ical-rs 0.7.3, mime-rs 0.2.1 and vcard-rs 0.6.1. `cargo update -p io-pimdir -p vcard-rs -p ical-rs -p mime-rs` moved those four alone in rust/Cargo.lock. Until io-pimdir 0.19.0 is on crates.io, a temporary `[patch.crates-io] io-pimdir = { path = "../../io-pimdir" }` closes rust/Cargo.toml.

## Breaking changes met

- **Drafts**: the schema and `init_store_meta` are io-pimdir's, so the app now creates storage-draft-15 stores and refuses a draft-14 one (`store-version`), never migrating it; the index fork rust/pimdir/queries/search/init_index_meta.sql writes search-draft-15 under the canonical text's new digest, and an index of draft-14 is rebuilt. The fixtures of rust/src/offline/store.rs, rust/src/offline/shape/tests.rs, rust/src/ffi/offline.rs and rust/src/search/tests.rs state draft-15, a draft-14 store, sync state and index among the refused or rebuilt ones, a draft-16 one standing for a later version.
- **`PimdirLoaded::folded`**: the load reply carries `folded` (rust/src/offline/wire.rs `LoadedJson`), the handles `PimdirHub::folded` answers, absent read as none.
- **`PimdirWritten::adopted` and `refused`**: the write reply carries both (`WrittenJson`), absent read as none.
- **`PimdirIndexGonePlacement::hash`**: read from the fourth column of the canonical `placements_gone`.
- **`PimdirIndexOp::DropUnplaced`**: run as two statements of the app's own, rust/pimdir/queries/index/drop_unplaced_object_text.sql and drop_unplaced_object_row.sql, `drop_object_text` and `delete_object_row` guarded by the canonical `object_placed` test, in the same batch as the ops before it.

## Left

- android/app/src/main/java/org/pimalaya/pimdir/PimdirStorage.java answers no `folded`, `adopted` nor `refused`: until it does, a body another source folded in reads as the source's own edit, and a pull a policy took over or kept apart is reported as before. The sync vectors of 0.19 were not run (SyncVectorsTest is Gradle's).
- The Java tests stating draft-14 (MailIndexTest, PimdirDbTest, PimdirStorageTest) and android/app/src/main/java/org/pimalaya/pimdir/PimdirDb.java's comment were not moved; Gradle was not run.

## Evidence

`nix develop --command bash -c 'cd rust && cargo test --locked'` with `PIMALAYA_IO_PIMDIR_SPEC` at the io-pimdir working tree's spec/, 2026-11-20: 738 passed, 1 ignored. `cargo fmt --check` and `cargo clippy --locked --all-targets`: clean.

## Capabilities moved

- offline-store (cairn/spec/offline-store.md): the schema, the store's version and its refusals, and the index at draft-15.
