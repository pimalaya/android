---
cairn: tasks
id: pimdir-engine
---

# Tasks

## Bridge

- [x] Port rust/src/offline.rs onto io-pimdir's coroutines: `Pimdir*` types, `load` for `storage`, the third drop reason, the sized lookup reply.
- [x] Add the typed summary to the wire, both directions, with its addresses.
- [x] Port rust/src/ffi/pimdir.rs onto `sql::all()`, hand the migrations over, and retire the reshaped-index entry point.
- [x] Update the lib.rs header: the engine is io-pimdir's.

## Store

- [x] PimdirSql: migrations, the named-parameter binder, no reshaped indexes.
- [x] PimdirDb: run every migration, reconcile tables, indexes and triggers against the canonical DDL.
- [x] PimdirItems: write the summary tables and the addresses; carry a source binding's revision.
- [x] PimdirStorage: load and upsert the typed summary, honour a `rekeyed` drop, answer the sized lookup.
- [x] PimdirMeta: derive the standard's summaries instead of the app's convention.
- [x] MailStore, EventStore, PimdirContacts: read the summary tables.
- [x] OfflineEngine: carry the summary on the mutation and the projected item.

## Proof

- [x] `cargo check` and `cargo fmt` on the crate.
- [x] `:app:assembleDebug` and `:app:testDebugUnitTest` green, tests updated to the new shapes.
- [x] Fold the delta into the spec and write the log entry.
- [x] Fix what the first live run against Fastmail caught: probes, and the provisional handle.
