---
cairn: change
change: follows-storage-draft-17
---

# Inventory: the evidence, measured 2026-11-23

Scratch: ~/code/pimalaya/.pimdir-migration/android/.

- base/ is `git archive HEAD` (c21fb53). src/ is the same tree with the pin, `features = ["indexer", "search", "vendored"]` (only to get past N1's link), and the compile patches of B.
- Specs come from `git archive` of io-pimdir v0.19.1 and 7f16b27.
- Logs are under logs/.
- Every run went through `nix develop /home/soywod/code/pimalaya/android --command …`, pinned to CPUs 10, 11, 12, 26, 27, 28, at `-j 4`, while other lanes built.

## Runs

| Run | HEAD on 0.19.1 | 7f16b27, compile breaks patched |
| --- | --- | --- |
| `cargo test` | 738 passed, 1 ignored (52 s) | 703 passed, 35 failed, 1 ignored |
| `:app:testDebugUnitTest` | 1,066 run, 0 failed, 5 skipped (163 s) | 1,066 run, 509 failed, 5 skipped |
| `:client:testDebugUnitTest` | 14, 0 failed | 14, 0 failed (from cache) |
| SyncVectorsTest | 195 cases: 156 passed, 0 failed, 39 unbound | 200 cases: 0 passed, 161 failed, 39 unbound |
| floor, store (`served_sql_holds_to_the_sqlite_floor`) | 0 failures | 101 failures, 82 statements |
| floor, index (`served_index_sql_holds_to_the_sqlite_floor`) | 0 failures | 326 failures |

- The 5 skipped are the measures (FillPageMeasure 2, AgendaMeasure 1, Draft09Measure 2): no H0 baseline was taken in this phase (task H0).
- The 509 JVM failures span 59 classes, the full list in logs/src-gradle-failed.txt:
  - by class: PimdirStorageTest 67, ContactEngineGoogleBooksTest 46, MailMoveTest 33, MailStoreTest 23, CalendarMirrorTest 23, TaskSyncTest 20, WritesAfterAPassTest 17, StorePerformanceTest 16, TaskWritesTest 15, PimdirContactsTest 15, FileStoreTest 14, MailBodiesTest 13, MessageIdNamingTest 12, SubmissionTest 11, and 45 more classes with 1 to 10 each;
  - by first error: `no such table: mail_summary` or `contact_summary` 352, an `object_hash` column 148, `Unknown pimdir statement 'LIST_GARBAGE_OBJECTS'` 1, `store.event_summary` 1, and draft-name and JSON assertions 7.
- The vector failures split as 151 on `NOT NULL constraint failed: items.keywords` (INSERT_ITEM seeding) and 9 on `object_hash` in RECOUNT_OBJECTS.

## A. Build

- `features = ["search"]` (today's line): E0432 `search::refresh` (rust/src/search/refresh.rs:22). At 7f16b27 the refresh is under `indexer`.
- `features = ["indexer"]`: `sql::search` unresolved (search/shape.rs:18, search/held.rs:22): N1.
- `features = ["indexer", "search"]`: `rust-lld: error: unable to find library -lsqlite3`, since `search` implies `client`.
- deny.toml:18-21 denies every git source. flake.nix:379-441 pins the spec by version tag, and its guard reads the git lock's `0.19.1` as equal.
- MSRV: io-pimdir and the app both declare 1.89.

## B. Compile breaks (with `indexer` and `search`)

| file:line | error |
| --- | --- |
| offline/merge.rs:219 | `server_object_id` missing in `PimdirBinding` |
| offline/wire.rs:150 | in `PimdirPlacement` |
| offline/wire.rs:1053 | in `PimdirHeldObject` |
| offline/wire.rs:1183 | in `PimdirRemoteMeta` |
| offline/wire.rs:490 | `PimdirWriteOp::LandPlacement` not covered |
| search/compile.rs:243 | `Label`, `Property`, `Mid` not covered |
| search/compile.rs:673 | `PimdirQueryDate::Instant` not covered |
| search/compile.rs:694 | `PimdirQueryDateUnit::{Minute, Hour, Month}` not covered |
| search/tests.rs:268 (test build) | `server_object_id` missing in `PimdirPlacement` |

## C. Rust tests failing on the patched tree (35)

- Draft names, 10:
  - offline::store: `a_store_of_this_draft_opens`, `a_refused_store_says_which_is_the_earlier`, `a_store_of_an_earlier_draft_is_refused`, `a_damaged_or_foreign_store_is_refused`.
  - search (`Rebuild { reason: "an index of search-draft-15" }`, search/tests.rs:94): an_index_of_another_shape…, a_hit_carries_a_snippet…, a_refresh_follows…, a_refused_query…, a_run_holds… and the agenda test.
- Schema, 14:
  - `ffi::offline::{served_sql_holds_to_the_sqlite_floor, a_store_a_newer_sqlite_wrote_opens_on_the_floor}` (`no such table: mail_summary`).
  - 12 offline::shape fixtures (`table items has no column named object_hash`, shape/tests.rs:132).
- Forks, 2:
  - `every_fork_names_the_canonical_text_it_rewrites` (`owner/ADD_BASE_FLAGS forks no canonical statement`).
  - `every_index_fork_names_the_canonical_text_it_rewrites` (INIT_INDEX_META moved).
- Coverage, 7: search::coverage, each on the index version.
- Search, 2:
  - `served_index_sql_holds_to_the_sqlite_floor`.
  - `the_search_vectors_answer_on_the_floor` (`store.json at another version: 3`).

## D. The app's SQL against what moved (file classes measured by grep, failures by the floor)

The floor's 82 statements: 54 of the app's own, 25 forks, and 3 canonical (ADD_PROPERTY and UPSERT_SUMMARY fail on `ON CONFLICT`, ROLL_COVERAGE on `FILTER`).

| class | files |
| --- | --- |
| flags | app/base_flags, count_owing, item_flags, item_object_flags, load_placements, load_placements_by_handle, load_placements_by_link, overruled_state, search_mail_indexed; owner/add_flags, add_base_flags, evict_before |
| D2 summary (23 app) | app/copy_addresses, copy_mail_summary, copy_summary, held_message_object, held_object, in_reply_to_of, items_without_summary, key_of_another_message, list_cards, list_live_mail, list_task_categories, list_task_rows, load_placements, load_placements_by_handle, load_placements_by_link, mail_of_message_id, mail_pins_dated_before, mail_unjoined, mail_witnesses, message_id_of, names_another_message, pending_creates_by_message_id, search_mail_indexed |
| summary (forks, index) | owner/evict_before, link_invitations_of, link_invitations_to, link_mail_from, link_parents_of, link_parents_to, link_senders_of, link_senders_of_by_link, link_sources_of, link_sources_to, upsert_{mail,contact,event,task,journal,file}_summary; index/agenda_occurrences, item_addresses, item_categories, item_in_reply_to |
| D4 pointers (58) | 51 app files and owner/cancel_action, delete_unbound_item, purge_item, purge_retained_before, recompute_refcounts, release_bases_before, release_before, and index/agenda_occurrences |
| D4 still prepare, answer ids | app/ancestor_object, binding_base_object, binding_conflict_object, binding_shared_object, bound_elsewhere, card_bound_elsewhere, item_binding_objects, item_bindings, item_conflict_object, set_item_conflict, write_binding |
| keywords CHECK | app/set_flags_known, set_base_flags_known, write_binding |
| chunk, thread | index/put_chunk; search/upsert_thread, upsert_message, prune_messages |

In total, 97 of the 178 SQL files name what moved: 64 of the 103 app files, 25 of the 39 owner files, 5 of the 23 index files and 3 of the 9 search files.

Fork digests against 7f16b27:

- 22 unchanged.
- D3, 19 moved:
  - store: cancel_action, delete_unbound_item, evict_before, link_invitations_of, link_invitations_to, link_mail_from, link_parents_of, link_parents_to, link_senders_of, link_sources_of, link_sources_to, purge_item, purge_retained_before, recompute_refcounts, release_bases_before, release_before;
  - search: init_index_meta, match_objects, upsert_placement.
- 10 gone: add_flags, add_base_flags, link_senders_of_by_link, the six upsert_*_summary, upsert_thread.

Statements bound by name, from the literals in android/*/src/main/java and the signatures.json of both versions:

- Java binds 227 names: 94 canonical, 30 forks and 103 of the app's own, plus the six `"UPSERT_" + kind + "_SUMMARY"` at PimdirSummary.java:87.
- Of the 94 canonical, 78 keep their parameters and columns.
  - INSERT_ITEM gains `keywords` (PimdirStorage.java:1538 and :1824, FileStore.java:681, PimdirContacts.java:814).
  - 15 are removed: DELIVER_BINDING, INSERT_ADDRESS, INSERT_CATEGORY, INSERT_IN_REPLY_TO, LIST_GARBAGE_OBJECTS, LOAD_{CONTACT,EVENT,JOURNAL,MAIL,TASK}_SUMMARIES_BY_LINK, REPLACE_ADDRESSES, REPLACE_CATEGORIES, REPLACE_IN_REPLY_TO, RETAIN_BASE_FLAGS, RETAIN_FLAGS.
  - LIST_*_PAGE_ASC, SEARCH_*, GET_* and the "rewritten in place" readers keep their signatures.
- Rust binds 59 canonical names: 56 keep their signatures, and 3 are removed (CHUNKS_OF, DELETE_THREAD, UPSERT_THREAD).
- Rust binds 44 canonical index statements unforked, and serves 52 more that nothing binds. Those 52 are the ones the index floor rejects (posting lists, `split`, `HIT_THREADS` `->>`, ROLL_COVERAGE).
- D7, compile.rs inline SQL on moved names: :315, :320, :327, :362, :375, :403, :444, :494, :497, :601-606 (15 lines). compile.rs:541 panics on a name not served.

consumers.md, re-derived:

- **Missing:** the object-id move (58 files), `item_property` and `server_object_id`, the 2 removed statements Java binds (DELIVER_BINDING, LIST_GARBAGE_OBJECTS), and the `search` feature break.
- **Wrong:**
  - FLAGGED, ATTACHED, HOLDING, CATEGORISED and OCCURRING_BETWEEN are not dropped; only their text joins `p.object`.
  - The 26 "replaced" names are 15 removed.
  - LIST_*_PAGE_ASC, SEARCH_* and GET_* stay.

## G. Bug sources (re-checked on c21fb53 where marked)

- G1: cairn/log/2026-11-13-a-read-verifies-and-a-send-leaves-its-receipt.md:28, against offline-store.md:718.
- G2: 2026-11-13-the-store-follows-pimdir-draft-10.md:29.
- G3: 2026-11-13-the-store-follows-pimdir-draft-11.md:31.
- G4: 2026-11-13-the-bridge-follows-the-io-releases.md:73. Re-checked: android/client/…/MailPage.java holds no `capped`.
- G5: 2026-11-18-the-app-follows-io-pimdir-0-16.md:19. Re-checked: rust/src/search/find.rs pages by offset.
- G6: 2026-11-18-the-low-lines.md:74 and :77.
- G7: pimdir cairn/changes/audit-2026-11-12/tasks.md:120 (A5). Re-checked: io-pimdir 7f16b27 src/client/schema.rs:26 holds `pub(crate) DERIVATION = 3`, and the app holds 3.
- G8: the a-read-verifies log, :29.
- F3 also closes the-bridge-follows-the-io-releases.md:74 ("`Native.relocateMessage` … answer nothing, so the destination UID … is dropped").
- A8 to A11 are closed (cairn/log/2026-11-19-the-audit-lines-a7-to-a11.md; pimdir audit tasks.md:123-126), and no vector fails at HEAD. A6 (the second implementation) is this proposal's D1.
- docs/pimalaya-android-plan.md holds no device-bug list (the only pointer to one, the-app-follows-neverest-v1/tasks.md:41, has no target).
- `gh issue list -R pimalaya/android --state all` answers none.
- Out of scope, on the surface but not touched by the port:
  - refusals shown in the UI (draft-13:68);
  - Graph `sentDateTime` deltas (2026-11-15:52);
  - Graph ids spliced raw (a-graph-message-is-read-raw:20; no failing id known);
  - the unbound vector verbs (rekey, heads, one-way policies, Abandon, stand-ins);
  - the cost models never measured on a device (docs/performance.md).

## Triage of the 71 change folders

Read from each folder's proposal.md and open lines, 2026-11-23. IN means the port touches its statements, or the folder holds an open line on the surface, which the step named here carries.

| id | verdict |
| --- | --- |
| a-message-is-filed-somewhere | IN: fold only (tasks.md:13) |
| a-search-says-what-it-covered | IN: the coverage statements move (D7 coverage tests); its UI lines (tasks.md:7-11) stay its own |
| account-settings-page | OUT: settings UI |
| attachments-as-files | IN: `file_summary` and `list_attachments` (D2); device test tasks.md:22 stays its own |
| attendee-notifications | OUT: connector flags |
| audit-s1-residuals | OUT: privacy nits, no store line |
| automatic-references | IN: the `link_*` rules and `invitation` (D2, D3) |
| background-check | IN: where `settled_collections` applies (F2) |
| body-loss-races | IN: `object_hash` and `base_object` in `applyUpsert` and `enqueue` (D4); BodyLossRacesTest guards; library asks proposal.md:26-28 out |
| body-run-loop | OUT: landed, nothing open |
| brand-palette | OUT: palette |
| bundled-sqlite | OUT: superseded by platform-sqlite |
| calendar-conflict-form | IN: `bindings.conflict_object` is an id (D4) |
| calendar-occurrence-writes | OUT: connector writes |
| calendar-zones-and-series | OUT: calendar logic; agenda reads covered by D7 |
| crash-and-surface-hardening | OUT: WebView and OAuth |
| drop-sync-mark | OUT: landed, nothing open |
| e2e-suite | IN: J1; `markersArePushedAndAKeywordIsKept` reads keywords (D1) |
| files-review-fixes | IN: blob paths keyed by hash, ids in rows (D4) |
| files-tab | IN: file statements (D2, D4) |
| fill-from-two-queues | IN: first-sync timings (H1, O2); device runs tasks.md:14, :29 stay its own |
| filter-page-account-hides | OUT: filter UI |
| gmail-first-sync-stall | IN: device measure tasks.md:19 (O2) |
| google-contact-refusals | IN: card_carried, card_awaited, mark_conflicted (D4) |
| google-delete-loss | OUT: landed; its tests stay as guards |
| google-sync | IN: live checks (J1); device measure tasks.md:45 |
| google-tasks-limits-said | OUT: UI copy |
| gtd-hardening | IN: `task_summary`, mail_of_message_id (D2) |
| guarded-contact-updates | IN: fold only |
| hide-in-recents | OUT: privacy UI |
| ical-rs-0-6 | OUT: parser bump |
| item-links | IN: `get_*`, `search_*`, describe_endpoint keep their signatures; app reads follow D2 |
| jmap-calendar-writes | OUT: connector |
| jmap-release-readiness | OUT: connector and onboarding |
| mailbox-refusal-skipped | OUT: landed, nothing open |
| mail-download-window | IN: counts, release and recount statements (D2, D4) |
| mail-filter-apply | IN: device test only |
| mail-filter-scope | IN: device test only |
| mail-load-more | IN: persisted bodies, `StoreObject` by id (D4, H6) |
| mail-load-more-graph | IN: vector 198 (another server object id) meets F4 |
| mail-load-more-imap | IN: live and device checks only |
| mail-load-more-jmap | IN: live and device checks only |
| mail-named-by-message-id | IN: tasks.md:20 (a move's target withdrawn, not landed) is F1 and F3; tasks.md:18 (rekey) out, the app runs none |
| mail-sync-robustness | IN: device test only |
| network-trust-hardening | OUT: network trust |
| network-warnings | OUT: network trust |
| new-task-page-and-drop-in | OUT: UI |
| oauth-hardening | OUT: OAuth |
| oauth-pending-grant-encrypted | OUT: nothing open |
| oauth-verification | OUT: OAuth |
| one-fastmail-consent | OUT: OAuth |
| one-filter-panel | IN, partly: tasks.md:22 (Starred and Answered need `marks` filters) stays its own feature |
| one-onboarding-path | OUT: onboarding UI |
| owner-lock-must | OUT: abandoned, the app takes no lock |
| phone-calendar-mirror | IN: phone bindings and drops (D4); device matrix stays its own |
| phone-contacts-mirror | IN: device test only |
| production-release | IN, partly: tasks.md:70-72 stay its own; :77 names storage-draft-14, stale |
| redesign-for-the-palette | OUT: UI |
| release-supply-chain | OUT: supply chain |
| sign-in-again | IN: count_owing reads the flag tables (D1) |
| store-performance | IN: H3; L2 is taken upstream, so MailStore.unjoined may go (D2) |
| store-performance-followups | IN: list_cards, the recounts and the mail pins (D2, D4) |
| sync-dock | OUT: sync UI |
| sync-feedback-pinned | OUT: sync UI |
| sync-strip-in-app-bar | OUT: UI |
| sync-strip-progress | OUT: UI |
| task-batch-actions | OUT: UI |
| tasks-in-google-and-microsoft | IN: phone-only task state lost on reinstall (proposal Users); `item_property` could hold it, out of scope |
| task-waiting-on-contact | IN: its planned statements join `item_address`, now `summary_value` |
| the-app-follows-neverest-v1 | IN: V0 to V6 (tasks.md:184-190) are this change's model, ticked by I1; L4 (tasks.md:145) re-checked by D1 |
| write-once-and-oauth-dos | OUT: OAuth device check alone |

Totals: 36 IN, 35 OUT.
