---
cairn: tasks
change: follows-storage-draft-17
---

# Tasks

Conventions:

- `R` is `nix develop --command bash -c 'cd rust && cargo test --locked'`.
- `J` is `nix develop --command gradle -p android :app:testDebugUnitTest :client:testDebugUnitTest --console=plain`.
- Both run from the repository root, pinned with `taskset -c 10,11,12,26,27,28`, `-j 4`.
- Until A3 lands, export `PIMALAYA_IO_PIMDIR_SPEC` from `git -C ../io-pimdir archive 7f16b27 spec` into the scratchpad.
- Commits follow frame.md §4: one per checked step group, unsigned, never pushed.
- journal.md and report.md carry `cairn: change` frontmatter: cairn/verify.sh accepts five types alone.
- A step failing its gate twice is parked in journal.md, and the run goes on.
- Line numbers are at c21fb53.

## 0. Baseline

- [x] H0. Run `PIMALAYA_MEASURE=1 gradle -p android :app:testDebugUnitTest --rerun --tests org.pimalaya.mail.FillPageMeasure --tests org.pimalaya.calendar.AgendaMeasure` at HEAD, under the measure lock on CPU 15, three runs. Gate: the per-page ms and the agenda ms are in journal.md.

## A. Dependency and build

- [x] A1. rust/Cargo.toml: add `[patch.crates-io] io-pimdir` at git rev 7f16b27 (frame.md §2). The dependency at :38 takes `features = ["indexer"]` in place of `["search"]`. The dev-dependency at :21 takes `["client", "vendored", "search"]`. Gate: Cargo.lock names `git+https://github.com/pimalaya/io-pimdir?rev=7f16b27…`, and `cargo build` fails on exactly inventory.md's B list.
- [x] A2. N1 (needs-io-pimdir.md). Take the io-pimdir commit that compiles `sql::search` under `indexer` once the io-pimdir lane names it. Otherwise fork the 44 bound index statements (inventory.md D8) into rust/pimdir/queries/search/, and hold `search-draft-17` as an app constant checked against `io_pimdir::sql::search::FORMAT_VERSION` in a test. Gate: `cargo build` links, `cargo tree -e normal -i rusqlite` answers nothing, and `cargo ndk -t arm64-v8a build --release` links.
- [x] A3. flake.nix:379-396 and :441: `ioPimdirSpec` by `rev = "7f16b27…"` with its new sha256, and the guard compares the lock's source rev, since the git lock still says 0.19.1. Gate: inside `nix develop`, `cat $PIMALAYA_IO_PIMDIR_SPEC/REVISION` prints f295ed7…, and `ls $PIMALAYA_IO_PIMDIR_SPEC/vectors/sync | wc -l` counts the 200 cases.
- [x] A4. deny.toml:18: `allow-git = ["https://github.com/pimalaya/io-pimdir"]` while pinned. Gate: `cargo deny check` (audit.yml) is clean.

## B. Compile breaks

- [x] B1. Add `server_object_id` to five literals: rust/src/offline/merge.rs:219, wire.rs:150, :1053 and :1183, and search/tests.rs:268. The wire carries an optional `serverObjectId` both ways, omitted when absent, and `None` from every connector until F4. Gate: builds.
- [x] B2. wire.rs:490: `PimdirWriteOp::LandPlacement` becomes `WriteOpJson::LandPlacement { collection, handle, landed, revision? }`, which Java applies in F1. `revision` is omitted when absent, since org.json reads a null as "null". Gate: builds.
- [x] B3. rust/src/search/compile.rs:243: arms for `Mid` (`store.summary.uid`), `Property` and `Label` (D5: `store.item_property`, the label values pre-read by the caller and folded in Rust as `category:` is). compile.rs:637-766: `bounds` calls `PimdirQueryDateRange::bounds`, and `span`, `start_of_day`, `days_from_civil` and `days_in_month` go. Gate: R builds, `cargo clippy --all-targets -- -D warnings`.

## C. Format strings and refusals

- [x] C1. Restate the draft names, never weakening an assertion:
  - Rust: rust/src/offline/store.rs tests (`a_store_of_this_draft_opens`, `a_refused_store_says_which_is_the_earlier`, `a_store_of_an_earlier_draft_is_refused`, `a_damaged_or_foreign_store_is_refused`), the index-version tests of search/tests.rs:94, and the search vector runner's store.json, now format 3 (search/tests.rs:336).
  - Doc comment: offline/shape.rs:23-26 becomes `storage-draft-17 owner`.
  - Java tests: the draft lines in MailIndexTest, PimdirDbTest and PimdirStorageTest.
  - Gate: those tests pass.
- [x] C2. V4 (the-app-follows-neverest-v1 tasks.md:188): StoreRefusedActivity.java and strings.xml:8-9 word the screen by the refusal's order: an earlier store asks for a reinstall, a later one for an update, and a later store is never offered for deletion. Gate: one Robolectric test per order, plus O1.

## D. The app's SQL (inventory.md D for every file)

- [x] D1. Flags:
  - The bridge answers a flag set's `marks` and `keywords` (`codec::keywords_to_text`): through the placement and base JSON of every write op, and through one `PimdirSql.flags` call for the Java writers.
  - SQL: app/set_flags_known.sql and app/set_base_flags_known.sql bind `:marks` and `:keywords` (`0` and `''` when unknown). app/write_binding.sql resets `base_keywords`. item_flags, base_flags, item_object_flags, overruled_state, count_owing, search_mail_indexed and load_placements* read `keywords`.
  - Deletions: owner/add_flags.sql and owner/add_base_flags.sql go, and so do the binds of `RETAIN_FLAGS` and `RETAIN_BASE_FLAGS` (PimdirItems.java:183-233).
  - `INSERT_ITEM` gets `keywords` at PimdirStorage.java:1538 and :1824, FileStore.java:681 and PimdirContacts.java:814.
  - Gate: a new test writing `$Forwarded work \Seen` and reading the set back whole, which fails before (NOT NULL `items.keywords`), plus R and the flag tests of J.
- [x] D2. Summaries:
  - rust/src/offline/summary.rs hands Java the row (`component`, the `summary` columns, a `values` list) and reads one back. No per-kind code is left in Java.
  - New owner/upsert_summary.sql: the 3.18 fork of `UPSERT_SUMMARY` (update, then insert where absent, invitation kept), with its digest. The six owner/upsert_*_summary.sql go.
  - PimdirSummary.java:54-190 binds `UPSERT_SUMMARY`, `REPLACE_VALUES` (`:lists`) and `INSERT_VALUE`.
  - DeletedItems.java:200-235 reads `LOAD_SUMMARIES_BY_LINK` and its `title` column.
  - The 23 app statements of inventory.md D2 are renamed with their column order kept, so the ordinal readers (PimdirStorage.placementOf :616-724 and the rest) do not move.
  - MailStore.unjoined goes if the canonical counts at 7f16b27 read no `summary` without the attachment chip (store-performance L2, taken upstream); otherwise it shadows `summary`.
  - Gate: R, PimdirStorageTest, DeletedItemsTest, MailStoreTest, and StorePerformanceTest `aCountWithNoChipJoinsNoSummary`.
- [x] D3. Re-derive the 16 moved store forks (inventory.md D3) from their 7f16b27 text, and restate each digest. owner/link_senders_of_by_link.sql goes, its canonical gone. Gate: `every_fork_names_the_canonical_text_it_rewrites`, `every_reference_rule_records_what_it_lacks`, `every_upsert_of_a_row_by_key_agrees`.
- [x] D4. Object ids: every statement of inventory.md D4 (58 files: 47 fail the floor, 11 still prepare) joins `objects` by id, hashes in and out. A new Rust test, `every_pointer_read_answers_a_hash`, seeds a draft-17 store with every pointer set, runs each app read naming one, and asserts 52-character base32 answers. It fails before on the 11. Gate: that test plus `served_sql_holds_to_the_sqlite_floor`, which must show 0 failures.
- [x] D5. `DELIVER_BINDING` and `LIST_GARBAGE_OBJECTS` become app statements (app/deliver_binding.sql and app/list_garbage_objects.sql, from their 0.19.1 text joined by id; N2). Gate: the PimdirStorageTest delivered-create cases and DeletedItemsTest.
- [x] D6. The queue's `RESTRICT`: app/anchors_queued.sql gives way to the canonical `COLLECTION_HOLDS_ACTIONS` (PimdirCollections.java:83), and the comments at :80 and MailStore.java:177 are restated. ADD_PROPERTY gets a 3.18 fork (`INSERT OR IGNORE`). Gate: a test deleting a mailbox that holds a queued submission keeps both, and the floor.
- [x] D7. Index (proposal D3):
  - Schema: rust/pimdir/migrations/search/0001_init.sql follows the canonical draft-17 tables in FTS4. That means `object.id` AUTOINCREMENT, `placement.object` with `placement_by_object` and `placement_by_seq`, a flag table that refuses system flags, no `chunk` or `thread`, and `message.thread` free.
  - Forks: re-derive search/init_index_meta, match_objects and upsert_placement. search/upsert_thread.sql and index/put_chunk.sql go.
  - Refresh: rust/src/search/refresh.rs:211, :713, :823 and :833 skip threads and chunks.
  - Snippets: rust/src/search/find.rs:353 cuts them with `search::chunk`.
  - Own statements: index/agenda_occurrences, item_addresses, item_categories and item_in_reply_to read `summary` and `summary_value`.
  - Inline SQL: the 15 lines of compile.rs (inventory.md D7) follow, and `Flag` of a system flag reads `i.marks`.
  - Served set: search/shape.rs `statements()` serves the bound names alone, and a new test checks that every name the bridge binds is served.
  - Gate: `served_index_sql_holds_to_the_sqlite_floor` and `every_index_fork_names_the_canonical_text_it_rewrites`; `the_search_vectors_answer_on_the_floor`, with no case unbound beyond those its runner lists today (relevance, threaded listing, another zone); the coverage tests; `a_hit_carries_a_snippet_of_its_body`.
- [x] D8. Measures: FillPageMeasure.java's `STAMPS` drops `placement_flag_stamp_insert`. measure/Draft09Measure.java goes: its draft-08 and draft-09 bench files are gone and it names the dropped tables. Gate: J compiles, 3 skipped.
- [x] D9. Gate of D: R green; J green; SyncVectorsTest at 0 failed, at least 156 passed, the ledger (sync-vectors.txt) restated; `:app:assembleDebug`. Commit.

## E. Inherited behaviour

- [x] E1. Search, a Rust test each: `~foo` refused with `reserved-form` and its message shown; `mid:<a@b>` with and without brackets; `date:2026-08-01T10:00` and `date:2h`; `label:` and `property:` over a seeded `item_property`; compile.rs:33-39 `Sort` named `NewestFirst` and `Title` (proposal D4). Gate: R.
- [x] E2. `WRITE_CHUNK` grows from 1,024 to 4,096. A test lands a 4,096-member write batch through PimdirStorage.applyWrites in one transaction and checks its JSON size. Gate: J; the size goes to journal.md.

## F. Sync

- [x] F1. PimdirStorage.applyWrites applies `landPlacement` as io-pimdir's client/write.rs:484-505 and hub.rs:754 do: taken means delivered; otherwise the provisional create is dropped `Superseded` and rebound to `landed` with its base. Gate: SyncVectorsTest's relocation cases pass, and the vector counts go to the spec (delta.md).
- [ ] F2. Proposal D6. The bridge serves `SETTLED_COLLECTIONS` to the background pass (BackgroundJob.java, SyncService.java and the per-domain steps). A settled collection whose server state is unchanged runs no engine. Gate: a test where such a pass writes nothing (StoreProfile 0 writes), and a staged flag or a refusal makes the collection run. Fixes, if it reproduces, low-lines:78 ("a stopped background run still lists and pushes").
- [x] F3. Proposal D7. `assigned` from IMAP `COPYUID`: rust/src/mail/imap.rs relocation, `Native.relocateMessage`, and MailEngine.java:1327 and :1335. Graph's move answers its new id. Gate: a MailMoveTest case landing the target create on the UID, which fails before.
- [ ] F4. Proposal D7. `server_object_id`: written by app/write_binding.sql and read by load_placements*, held_object and held_message_object. It is stated by gmail.rs (the message id), jmap.rs (`Email/id`), imap.rs (`EMAILID` under `OBJECTID`) and msgraph.rs (the message id). Gate: a test where two Gmail messages sharing `Message-ID`, subject, date, sender and size never share a body, which fails before.

## G. Bugs (each reproduced by a failing test first; sources in inventory.md G)

- [x] G1. `PimdirItems.blobFile` follows a link, and `FileStore.saved` serves a blob unverified. Both go through the bridge's verified read (offline-store.md:718).
- [ ] G2. A restore from Deleted items whose stored summary disagrees with the body makes a second row: revive the held row.
- [ ] G3. Two messages reusing one `Message-ID`: the reader of the second lists the first's stand-ins, and a section fails to open.
- [ ] G4. An IMAP listing holding an unreadable line fails its mailbox's round on every pass: apply what was read and park the line.
- [x] G5. Search paging by offset repeats or hides a hit arriving between two pages (rust/src/search/find.rs): page by `PimdirSearchCursor`, which builds without `client` now.
- [x] G6. BackgroundJob.java's `stopped` flag is never reset when the service instance is reused, and a stop between `allowWaits()` and `jobFinished` leaves the waits cut.
- [x] G7. A5: a test compares the app's `DERIVATION` with a store io-pimdir's `PimdirStore` creates (N3).
- [x] G8. A body set aside with no network leaves its row saying it is held.
- [ ] G9. Gate of E to G: R, J, `:app:assembleDebug`, and `:app:assembleRelease` (R8: the new `Native` methods kept). Commit per item.

## H. Performance (thresholds against H0)

- [ ] H1. First sync: FillPageMeasure at or below 1.10 times H0 per 500-message page.
- [ ] H2. Background no-op: a new PIMALAYA_MEASURE test runs a pass over 40 unchanged mailboxes. After F2 it runs 0 engine runs and 0 store writes; record the ms.
- [ ] H3. Mail list pages: the StorePerformanceTest query plans stay unchanged on 3.18.2.
- [ ] H4. Search: a measure of the refresh's µs per message and of an `is:unread`, `from:` and two-word page on 10k messages, at or below the HEAD index.
- [ ] H5. Port io-pimdir eee1c47 to PimdirStorage.loadCollection: a load naming no link reads no hub. Gate: fewer statements per no-op delta (StoreProfile) and the vectors unchanged.
- [ ] H6. `body_flush`: no port. Write the owner's phone measure of a first sync's body writes (O2) to docs/performance.md.

## I to L. Docs, tests, release, report

- [ ] I1. Fold delta.md into cairn/spec/offline-store.md and mail.md, and write cairn/log/<date>-follows-storage-draft-17.md. Tick the V0 to V6 lines this change closes in cairn/changes/the-app-follows-neverest-v1/tasks.md:184-190. CHANGELOG.md gets no line: the release has not shipped. Run cairn/verify.sh.
- [ ] J1. Live, under the owner's grant: `gradle -p android e2eTest -Pe2e.targets=fastmail` (pull-only). No local servers are needed. Cross-app: none, since the store is private; `aStoreTheCanonicalTextWroteOpensAsTheSameStore` passes at draft-17.
- [ ] K1. Once io-pimdir 0.20.0 is published by its tag workflow:
  - rust/Cargo.toml takes `version = "0.20.0"` and drops the patch.
  - deny.toml drops `allow-git`.
  - flake.nix pins the spec by tag.
  - Gate: R, J, assembleRelease.
  - The pimdir tags and the app's own release are the owner's.
- [ ] L1. Write report.md: counts, measures, parked steps, the owner's checks.

## O. Owner checks on the phone, one at a time, in order (never driven by adb)

- [ ] O1. Install the debug APK over the current app. The reinstall screen shows the reason `store-version (storage-draft-15, earlier than this app's storage-draft-17)`, and nothing else opens.
- [ ] O2. Uninstall without keeping data, install, and add the Fastmail test account (pull-only). Check:
  - the first sync's duration;
  - the inbox, a contact, the agenda;
  - search: `is:unread`, `from:`, a body word, `mid:`.
- [ ] O3. After F2, leave the phone idle for one night, then read the background passes' durations in logcat.
- [ ] O4. After F3, on the Google or Microsoft test account, in a folder the check creates: move a message. It shows once in the target.
- [ ] O5. Install the release APK built by K1 and repeat O2's list.
