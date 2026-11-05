---
cairn: log
change: the-shape-follows-storage-draft-09
date: 2026-11-05
---

# The shape follows storage-draft-09

io-pimdir moves from 0.9.0 to b25523d (pimdir 1d34d2f: storage-draft-09, sync-draft-08, search-draft-09), pinned through `[patch.crates-io]` in rust/Cargo.toml until it is released. Every placement is keyed by an integer id its children carry, and the system flags are bits of the row (pimdir cairn/changes/storage-draft-09, settled by this app's own measurement in pimdir cairn/changes/performance-audit-2026-11-05: −45% a page, 28 to 16 statement runs a message). A draft-08 store is refused: the app opens on its reinstall screen.

Capabilities moved (offline-store):

- **MODIFIED** "The store's shape follows the canonical schema", "A store of another draft is not opened" and its scenarios: draft-09, a draft-08 store refused.
- **ADDED** "A delta loads what owes a push": `PimdirLoadSelection::Owing` reaches Java as `{"kind": "owing", "handles": [...]}`; `PimdirStorage.owing` resolves the named handles, those `list_owing_bindings` names (by `bound_handles`) and the link ids `list_lacking_items` names, read by `load_placements_by_link`. A round's last page and a rekey keep the whole collection. Guarded by PimdirStorageTest `anOwingLoadAnswersWhatItNamesAndWhatOwesAPush`, which the whole-collection load fails.
- **ADDED** "A flag set's system flags are the row's bits": `set_flags_known` and `set_base_flags_known` take the whole set in the `flags` slot and write its bits; the forks of `add_flags` and `add_base_flags` skip the system flags; every read rendering a set (`item_flags`, `base_flags`, `load_placements*`, `item_object_flags`, `overruled_state`, `search_mail_indexed`) joins the bits and the rows, and `search_mail_indexed` tests `\Seen` on the bit. Java binds `marks` 0 on `insert_item`, `writeFlags` setting the bits. Guarded by BodyLossRacesTest and DeletedItemsTest, which read the bits back, and the bridge's shape scenarios.

The shape (rust/pimdir/queries/): the 22 forks whose canonical text moved are forked again from it, digests restated, `link_senders_of_by_link` forked anew (its `ON CONFLICT DO NOTHING`) and run by `the_sender_rule_once_per_page_records_what_it_lacks`; about 70 statements of the app's own reach a child by the item's id; `init_index_meta` writes search-draft-09; the app's search compiler (rust/src/search/compile.rs) joins summaries, categories and addresses by the item's id. The flag scenario of the shape tests now holds keywords, the canonical slot holding no system flag.

Not done: the app's write path does not run `link_senders_of_by_link` per page yet, nor the canonical delta bench; measure/Draft09Measure.java still times draft-08 against the candidate and is left as the record it is. No phone run yet.

`cargo test` 607 passed; `:app:assembleDebug` and `:app:testDebugUnitTest` (863 tests) green.
