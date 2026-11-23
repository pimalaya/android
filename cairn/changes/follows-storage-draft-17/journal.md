---
cairn: change
change: follows-storage-draft-17
---

# Journal

One line per decision or parked step, timed (2026-11-23 unless stated).

- 16:47 Lane started on 5a692f8. Scratch ~/code/pimalaya/.lanes/android (run.sh, logs/, spec-7f16b27 from `git archive 7f16b27 spec`, REVISION f295ed7, 200 sync vectors).
- 17:35 H0 at 5a692f8 (io-pimdir 0.19.1), ~/code/pimalaya/.lanes/android/measure.sh: `gradle --no-daemon` under the measure lock, the JVM pinned to CPU 15 alone (JIT and GC share it, so absolute ms run about 2x a free JVM), other lanes building. Three runs, 500-message pages, first then five more:
  - FillPageMeasure stamped: 442 425 346 388 376 362 / 577 563 501 517 418 498 / 417 360 380 545 503 522 ms; median of pages 2 to 6: 425 ms.
  - FillPageMeasure unstamped: 272 304 230 250 198 187 / 336 363 310 297 297 309 / 372 365 328 345 276 305 ms; median of pages 2 to 6: 304 ms.
  - AgendaMeasure (478 entries): 10 days, index median 17, 18, 15 ms; 3 months, index median 48, 69, 66 ms; expanded 3 months median 70, 74, 71 ms; first index build 1896, 2550, 2191 ms.
  - A first daemon-reusing set (not pinned to CPU 15) is discarded.
- 17:36 io-pimdir drop 1 is cc3021b (lead, 17:30): A1 pins it straight away with `features = ["indexer"]` (A2's fork fallback not needed).
- 17:55 A1: pinned cc3021b, `indexer` alone, the dev-dependency `client`, `vendored`, `search`. `cargo build` failed on exactly inventory.md's B list (8 lib errors).
- 17:58 A2: no fork needed (drop 1). `cargo tree -e normal -i rusqlite` prints nothing; `cargo ndk -t arm64-v8a build --release` links (138 s).
- 18:00 A3: flake.nix pins the spec by `ioPimdirRev` (sha256 1vqwid7…), the guard reading the lock's `source` rev; `$PIMALAYA_IO_PIMDIR_SPEC/REVISION` is f295ed7, 200 sync cases.
- 18:02 A4: deny.toml allows the io-pimdir git source. `cargo deny check` also failed on RUSTSEC-2026-0592 (url 2.5.8, already at HEAD): `cargo update -p url --precise 2.5.9`, a lock-only bump; then clean.
- 18:05 B3/D5 (E1's `label:`): the spellings come from the canonical LIST_PROPERTY_VALUES, read once per query and folded in Rust, as `category:` reads TASK_CATEGORY_VALUES; a term holds a live placement whose seq holds one under `pimdir/label`.
- 18:20 B1: `server_object_id` crosses the wire both ways (`serverObjectId` on a placement and a remote item, `server_object_id` on a held object and a merge binding), omitted when absent.
- 18:25 D1: the bridge answers a flag set's columns through `PimdirSql.flags` (`Native.pimdirFlags`, `codec::marks_to_int` and `keywords_to_text`), refusing a flag the store cannot hold (`codec::is_flag`), as the CHECK of the dropped flag rows did. set_flags_known and set_base_flags_known bind `:marks` and `:keywords`.
- 18:30 D2: the summary wire is the row itself, `{component, <summary columns>, values[]}`, Rust's `SummaryJson` both ways; Java binds it by name into UPSERT_SUMMARY and the values into REPLACE_VALUES (`:lists` 1) and INSERT_VALUE. The envelope summary Java builds (`PimdirSummary.mail`) and the file rows follow it. A row of an unknown component reads as a message's.
- 18:30 D2: MailStore.unjoined stays: the canonical counts at cc3021b still read `summary` under `:attachment IS NULL OR EXISTS`, so the shadow (now of `summary`) keeps the plan off it; StorePerformanceTest asserts no `TABLE summary` in the plan.
- 18:35 D6: ADD_PROPERTY's fork is an insert where the value is not held (NOT EXISTS), not the plan's `INSERT OR IGNORE`, which would swallow a key or value past its bounds (the CHECKs); a shape test tells the two apart.
- 18:40 D3/floor: the store's own ROLL_COVERAGE (an io-pimdir own statement, `FILTER`) gets a 3.18 fork in read/, run beside the canonical text by a new shape scenario; nothing binds it, but the store shape serves every statement of `sql::all()`.
- 18:45 D7: search/shape.rs serves the canonical search statements of `BOUND` alone (49 names, OCCURRENCES_BETWEEN for the agenda vectors among them); `every_name_the_bridge_binds_is_served` holds the list to the names the bridge and its runners bind. EXTRACTOR goes to `pimalaya-android 2` (no chunk rows).
- 18:50 E1 (part): `Sort` is `NewestFirst` and `Title`; the bridge reads `title` (and the app's earlier `name`). The search vectors at queries.json v5 add oldest-first and sender cases: the runner lists them unbound (proposal D4), 7 unbound now (relevance, threaded, 2 zones, oldest-first, 2 sender) where 4 were.
- 18:55 Rust: 742 passed, 0 failed, 1 ignored; clippy `--all-targets -D warnings` clean. `every_pointer_read_answers_a_hash` fails on the HEAD text of the 11 statements (ITEM_CONFLICT_OBJECT answers Integer(3)).
- 19:10 J first run on the port: 1,065 run, 72 failed (59 PimdirStorageTest on an old-shape test fixture, 8 contacts on raw test SQL, SyncVectorsTest 2 departures: 199 and 200, the server object id), 3 skipped; from 509 failed at the plan's measure.
- 19:15 F4 (store half, pulled into D9 for vectors 199 and 200): write_binding writes `:server_object_id` from the placement's `serverObjectId`, load_placements* read it back (last column), held_object and held_message_object answer the binding of the run's source; the lookup yield now names the collection the run last loaded (`Driver.loaded`), so Java binds `:source`. Connectors still state none (F4's connector half).
- 19:15 F1 (pulled into D9): `landPlacement` applies as io-pimdir's write does: LINK_FOR_HANDLE of `landed` taken records the create delivered; else app/land_binding.sql moves the provisional binding to `landed`, based on the item's flags and body and the revision, its pins recounted by adjustRefcount.
- 19:20 C2: the refusal answers `order` (earlier, later, unknown, same) beside `detail`, left out for codes other than `store-version`; a later store shows "Update the app" and never the reinstall advice; earlier, unknown and same keep the reinstall screen (default taken: only a later store is known to open after an update).
- 19:45 F3: the IMAP relocation answers the UID its `COPYUID` states (MOVE or COPY, a skipped COPY stating none), Graph's the moved message's new id; JMAP and Gmail keep their handle and state none. `Native.relocateMessage` answers `{assigned}`, MailEngine passes it as the push result's `assigned`, and the engine's `LandPlacement` lands the target's create on it (MailMoveTest `aMoveStatingItsCopyUidLandsTheTargetCreateOnIt`; the Rust fake now states `COPYUID`).
- 19:45 E1: `the_draft_17_query_forms_answer_on_the_floor` (mid: both spellings, an instant, `2h` against a now, label: folded, property: byte for byte, `~foo` refused `reserved-form` over 0..4). E2: `aWriteChunkOfFourThousandMembersLandsInOneBatch` (size printed to the test's stdout, journaled once read).
- 19:45 J iterations run with `-x :client:cargoNdkBuild` (the unit tests load the host library; the four NDK builds cost ten minutes a run); the gate runs J whole.
- 20:05 E2: a 4,096-member write batch (contact upserts) lands in one applyWrites: 1,365,501 bytes of JSON (333 bytes a member).
- 20:05 J (NDK builds skipped): 1,069 run, 1 failed (SyncVectorsTest: 199 and 200, the scripted remote stated no server object id to the engine), 3 skipped.
- 20:10 F4 (connector half): a listed member states `serverObjectId` (Rust `Named`, the Java `Named` and `NamedStepItem` mirrors): Graph's and Gmail's message id, JMAP's `Email/id`, each equal to the handle. IMAP's `EMAILID` is parked: io-imap 0.12 and imap-types parse no `EMAILID`/`OBJECTID` (an io-imap ask, not io-pimdir's). Test: MessageIdNamingTest `twoMessagesStatingTheirServerIdsNeverShareABody`.
- 20:55 D9 gate: R `cargo fmt --check`, clippy `-D warnings`, 744 passed 0 failed 1 ignored; J 1,070 run, 0 failed, 3 skipped (client 14 green); SyncVectorsTest 200 cases: 161 passed, 0 failed, 39 unbound (ledger unchanged); `:app:assembleDebug` built (five APKs). Spec fold in cairn/spec (offline-store.md, mail.md) for what landed.
