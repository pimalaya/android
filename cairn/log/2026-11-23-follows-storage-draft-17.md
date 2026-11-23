---
cairn: log
change: follows-storage-draft-17
date: 2026-11-23
---

# The app follows storage-draft-17, sync-draft-15 and search-draft-17

cairn/changes/follows-storage-draft-17, shape A: the Java store and the app's own SQL ported to io-pimdir master past 7f16b27 (drops cc3021b and 2314084, then 17d4ea8, the 0.20.0 release candidate), the store private to the app, so no other app gates it. A build on it refuses every store an earlier draft wrote: testers and the owner reinstall (sync and empty the outbox first).

## Dependency and build

- rust/Cargo.toml requires io-pimdir 0.20.0 and patches it by git rev 17d4ea8 until the release is published, the library taking `indexer` alone, the dev-dependency `client`, `vendored` and `search`. No rusqlite in the normal tree; the release library links on arm64-v8a.
- flake.nix pins the spec by the same rev (`ioPimdirRev`), its guard reading the lock's `source` rev. deny.toml allows the io-pimdir git source while it is patched; url moved to 2.5.9 (RUSTSEC-2026-0592, already at the previous head).

## Store

- Flags: a set's system flags are `marks`, every other flag `keywords` (`base_*` for a base), both computed by the bridge (`PimdirSql.flags`); the flag row statements are gone.
- Summaries: one `summary` row and `summary_value` rows; the wire is the row itself (`component`, the columns, `values`), bound by name into the `upsert_summary` fork (3.18), REPLACE_VALUES and INSERT_VALUE; Java keeps no per-kind column code.
- Object ids: every app statement names a body by its hash and joins `objects` by id; `every_pointer_read_answers_a_hash` guards the reads (it fails on the previous text of the 11 that still prepared).
- Forks re-derived (16 moved), new forks for UPSERT_SUMMARY, ADD_PROPERTY (an insert where the value is absent, any CHECK still raising) and ROLL_COVERAGE (`FILTER` as a `CASE`); DELIVER_BINDING and LIST_GARBAGE_OBJECTS are app statements; the queue's `RESTRICT` read through COLLECTION_HOLDS_ACTIONS.
- `LandPlacement` lands a relocation's create on the handle the server stated (IMAP `COPYUID`, Graph's move id), `land_binding`. `server_object_id` is written, loaded and answered per holder of the asking source; Gmail, JMAP and Graph state it, IMAP states none (io-imap reads no `EMAILID`).
- The refusal answers its `order`; a store a later version wrote asks for an update and is never offered for deletion.

## Index and search

- index.db follows the draft-17 tables in FTS4: `object.id` AUTOINCREMENT, `placement.object`, a keyword-only flag table, no `chunk`, `thread` or `split`; snippets cut from the body by `search::chunk`; system flags read `items.marks`. The shape serves the 50 canonical statements the bridge binds (`BOUND`) and no other.
- `mid:`, instants and relative hours in `date:`, `label:` and `property:` (over `item_property`), `reserved-form`; sorts are `NewestFirst` and `Title`.

## Bugs closed

- G1: a saved file is served only from a blob whose bytes hash to its name, never through a link.
- G2: a restore from Deleted items into its own collection revives the row even over a stored summary disagreeing with the body.
- G3: a message whose key another message holds lists its own parts, never the other one's stand-ins.
- G5: a search pages by the last hit's place (`PimdirSearchCursor` token), never an offset.
- G6: a reused background job starts unstopped, and waits run again at each run and each interactive pass.
- G7: the app's DERIVATION is checked against the store io-pimdir creates.
- G8: a body set aside with no network leaves its row bodiless.
- A search test flake (`Owned` on a store reopened while another test's forked child held the owner lock until its exec): the tests reopening a store hold one throughout.
- H5: a load naming no link reads no placement, as io-pimdir eee1c47.

## Measured

Rust 746 tests pass (1 ignored); JVM 1,075 run, 0 failed, 3 skipped (the measures); the sync vectors at 17d4ea8: 213 cases, 161 pass, none fails, 52 unbound (the 13 new rekey cases among them); the search vectors: 298 run, 7 unbound. `:app:assembleDebug` and `:app:assembleRelease` build; the release library links with no second SQLite. A first-sync page of 500 messages (FillPageMeasure, CPU 15): median 249 ms against 425 at the previous head (0.59x); the agenda's index reads and first build faster too. The journal holds every figure.

## Left

Parked with their reasons in the change's journal.md: F2 (settled collections, a per-provider no-change probe) and H2 with it, F4's IMAP half (io-imap reads no `EMAILID`), G4 (an IMAP listing's unreadable line), H4 (no search baseline taken), H6 (the owner's phone measure), K1 (the published 0.20.0). Nothing was installed on a device and no account was reached: the owner's checks are O1 to O5 of tasks.md.

Capabilities moved: offline-store (the shape, flags, a store of another draft, the index, the sync vectors; a relocation lands its create and a binding keeps the server's object id, new), mail (the search reads the index).
