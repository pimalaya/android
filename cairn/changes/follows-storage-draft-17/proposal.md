---
cairn: change
id: follows-storage-draft-17
status: active
created: 2026-11-23
---

# The app follows storage-draft-17, sync-draft-15 and search-draft-17

## Why

io-pimdir master 7f16b27 implements pimdir e40b258: storage-draft-17, sync-draft-15, search-draft-17. The shared frame is pimdir cairn/changes/the-consumers-follow-draft-17/frame.md (target, pin, git, CPUs 10, 11, 12, 26, 27, 28, accounts, stop rules). The app is the heaviest consumer: its store is a second implementation, a Java owner running the app's own SQL (rust/pimdir/queries/, 178 files) on the platform's SQLite 3.18, on io-pimdir's `sql::MIGRATIONS`, which move with the pin. The store is private, so no other app gates it.

## What moved since 0.19.1

pimdir's consumers.md covers only the night of 2026-11-22; io-pimdir's Unreleased CHANGELOG adds more, and inventory.md measures the whole:

- **Object ids:** every body pointer holds `objects.id`, not the hash. Statements that still prepare now answer integers where Java expects hashes, the one class that fails silently.
- **Flags and summaries:** `items.keywords` and `bindings.base_keywords` replace the flag tables; one `summary` and one `summary_value` table replace the six per-kind tables and their lists.
- **New store objects:** `item_property` with its triggers, the trigger-kept `owes` and `lacks` marks, `bindings.server_object_id`, `queue.collection` `ON DELETE RESTRICT`.
- **Index:** `placement.object` replaces the hash, `split` arrives, `chunk` and `thread` go, and the flag table holds keywords alone, so `is:unread` reads the store's marks.
- **Engine and query language:** `LandPlacement`, `server_object_id`, `settled_collections`, `mid:`, instants, `label:`, `property:`, `reserved-form`.
- **Statements Java binds:** 15 canonical ones are gone (consumers.md missed `DELIVER_BINDING` and `LIST_GARBAGE_OBJECTS`), `INSERT_ITEM` gains `:keywords`, and 78 of the 94 keep their signatures. consumers.md's 26 "replaced" names are these 15.
- **Features:** `search` now implies `client`, so the release library cannot take it (needs-io-pimdir.md N1).

Measured on a scratch copy at 7f16b27, with only the compile breaks patched:

- Rust: 35 of 738 tests fail; the floor rejects 82 statements (54 of the app's own, 25 forks, 3 canonical).
- JVM: 509 of 1,066 tests fail.
- Sync vectors: 0 of 200 pass, 161 fail, 39 unbound.

The baseline at HEAD: 738 Rust tests, 1,066 JVM tests (5 skipped), 156 of 195 vectors passing (39 unbound).

## D1. The shape: A, the port (default), or B

- **A. Port the Java store and its statements.**
  - Size: 66 of the 103 app statements, 29 of the 51 forks plus 2 new ones, 5 of the 23 index statements, about 12 Java classes (read from code), the bridge's wire and summary mirror, and the index shape. That is three to five focused days plus fixtures.
  - Deletes: the 6 per-kind summary forks, the flag statements, the thread and chunk statements, and about 110 lines of date arithmetic in rust/src/search/compile.rs that `PimdirQueryDateRange::bounds` now provides.
  - Needs from io-pimdir: N1 alone.
  - Risk: the second implementation stays, so every engine rule still lands twice (audit A6).
- **B. Serve the store from io-pimdir in Rust, its SQLite bundled, Java reduced to the provider bridges** (pimdir a-smaller-sync design.md §8).
  - Deletes: the Java store (the pimdir package's 9,389 lines), wire.rs and merge.rs, and the FTS4 index. W1 to W3 and C7 become internal.
  - Costs: it reverses the owner's 2026-10-31 platform-SQLite decision, adds about 1.9 MB per ABI, and replaces every list screen's SQL with reader calls over JNI. It also needs io-pimdir's owner lock reconciled with the 2026-11-13 no-lock decision. That is weeks, not days.
  - B would be its own proposal (suggested id: the-store-served-by-io-pimdir). This change keeps A, which is sound: the vectors measure the Java half.

## Scope

In: A to L of the frame as they apply, the bugs of tasks.md G (store, sync or search surface, each with a regression test), and the performance items of H.

Out, with the reason:

- The rekey defects R0 to R8: the app runs no rekey.
- The drain defect K4: the app queues submissions alone, and moves go through `store.mutate`.
- The `pimdir` binary's JSON contract: the app ships no CLI.
- `body_flush`: Java writes its own blobs, and a port needs a device measure first (H6).
- The cross-app check: the store is private. The canonical-text store test stands in for it.
- The off-surface open lines: see the triage in inventory.md.

## Decisions for the owner, each with the default taken

- **D2. Features.** `indexer` without `search`, and N1 asked of io-pimdir. If N1 is not released when step A2 runs, fork the 44 bound index statements, then drop the forks with N1.
- **D3. Index tables.** Follow the canonical draft-17 tables: no `chunk` or `thread` table, snippets cut from the body, spans read from the store. Keeping the app's chunk and thread tables is the alternative.
- **D4. Sort orders.** No UI change: the two orders keep their labels, named `NewestFirst` and `Title` in the bridge. Oldest-first and sender orders would be a UI change, and relevance needs `bm25`, which FTS4 lacks.
- **D5. `label:` and `property:`.** Answer them from `store.item_property`, empty until the app writes properties. Refusing them is the alternative.
- **D6. `settled_collections`,** beyond what the migration forces: the background job skips settled collections the server states unchanged (step F2).
- **D7. Relocations and server ids,** beyond what the migration forces, for data safety: IMAP `COPYUID` and Graph's move id state `assigned`; Gmail, JMAP, IMAP `EMAILID` and Graph state `server_object_id` (steps F3 and F4).
- **D8. Upgrade.** Keep the reinstall screen, its wording following the order (V4). A "start over" button that moves the store aside would be new behaviour.
- **D9. Device floor.** No API 26 emulator. The host runs SQLite 3.18.2 with Android 8's flags, and the owner checks on the phone.

## Risks

1. **Silent wrong answers the floor cannot see.** 11 app statements read or write body pointers and still prepare. The search's system-flag terms would answer nothing once the index holds keywords alone. Guards: the pointer test of step D4, the 200 vectors, and the search vectors.
2. **N1 not released.** The release library then cannot link, and the 44-fork fallback adds churn.
3. **The Java half diverging from io-pimdir** on a rule no vector covers: io-pimdir's sync oracle never runs the Java store. The new `LandPlacement` and `server_object_id` code is where this is likeliest.

Users: the app has no public release (CHANGELOG.md holds Unreleased alone). On the owner's phone and on testers' phones, the draft-15 store meets the reinstall screen. Reinstalling loses what the old store held alone: an unsent outbox, staged changes, and the phone-only task state (strings.xml:89). Before installing, sync and empty the outbox.
