---
cairn: log
change: the-app-follows-neverest-v1
date: 2026-11-18
---

# The store follows pimdir draft-14

Task V-draft-14 of cairn/changes/the-app-follows-neverest-v1: the app moves from pimdir draft-13 to storage-draft-14, sync-draft-14 and search-draft-14 (pimdir da7e01a) on io-pimdir 0.17.0, and onto io-jmap 0.10.1, both released on crates.io on 2026-11-18. The previous draft move (cairn/log/2026-11-16-the-store-follows-pimdir-draft-13.md) is the model. Draft-14 is not the v1 format: the production track still waits for storage-v1.

## The manifest

rust/Cargo.toml asks io-pimdir 0.17.0 (the dependency and the dev-dependency) and io-jmap 0.10.1. `cargo update -p io-pimdir --precise 0.17.0` and `cargo update -p io-jmap --precise 0.10.1` moved those two alone in rust/Cargo.lock (io-pimdir 0.16.0 to 0.17.0, io-jmap 0.10.0 to 0.10.1). No `[patch]`, git or path source.

## The draft

The store is created, checked and stamped at draft-14 by the crate's names (`sql::FORMAT_VERSION`, `SYNC_VERSION`), so a draft-13 store an earlier build wrote is refused (`store-version`, or `sync-version` for its sync state) and the app opens on its reinstall screen, as at the draft-13 move; an index of an earlier draft is rebuilt. The conformance statement (rust/src/offline/shape.rs) reads `storage-draft-14 owner (own statement text), sync-draft-14 engine`; java/pimdir/PimdirDb.java names storage-draft-14.

Three canonical statements changed their text between v0.16.0 and v0.17.0, the draft names alone but for one comment:

- `storage/owner/init_store_meta.sql` (`storage-draft-14`): run verbatim, the app keeps no fork.
- `storage/owner/refuse_binding.sql` (its comment alone: an engine binds a create the store offered first): run verbatim, the app keeps no fork.
- `search/init_index_meta.sql` (`search-draft-14`): forked for the `pimalaya-android` shape. The fork, rust/pimdir/queries/search/init_index_meta.sql, was read again against the new text and names the new digest (0bb135b9cb8cc214); only the draft's name moved.

`enqueue_with_body` now strips `X-PIMDIR-` properties and `enqueue_foreign_body` is gone: the app calls neither, nor any `PimdirProducer` method (they are in the `client` feature the bridge does not take; the dev-dependency takes it and no test calls them either).

## The version order

- **The store** (rust/src/offline/store.rs): a store refused for its `format_version` now says which of the two is the earlier, by STORAGE §4.2's order, through io-pimdir's `version::PimdirFormatRefusal::order` (`PimdirFormatOrder`): the refusal's detail reads `storage-draft-13, earlier than this app's storage-draft-14`, `..., later than ...`, or `..., of no order beside ...` (a store naming none reads as earlier). The crate's own wording (`Display`) is not used: it tells a command-line user to sync with the old engine and move the directory aside, which the app cannot offer. The code stays `store-version`, and the reinstall screen shows the detail as its reason. A test runs spec/vectors/versions.json (24 cases) through it.
- **The index** (rust/src/search/held.rs): versions order as SEARCH §2 says, drafts by their number, every draft below every release, releases by their major (`order`, over `sql::search::FORMAT_VERSION` rather than `FORMAT_DRAFT`), so a release-built app rebuilds a draft index and refuses a later release's. For this draft build the outcome is unchanged: search-draft-13 is rebuilt, search-draft-14 is current, search-draft-15 and search-v1 are refused. This is task V3's ordering, done here.

## A tombstone over a conflict

SYNC §3 at draft-14 puts `Tombstone` first: a source binding a deleted item projects a tombstone whatever conflict stands, carrying its binding's `conflict_revision` (a server edit it names beats the delete), and an item's conflict holds no delete back. java/pimdir/PimdirStorage.java `statusOf` projected `conflict` first, so a delete made on one source over an item conflict was never pushed to the other, and a tombstone over a conflicted binding never derived the revival its server edit asks. The rule now runs tombstone first; `conflictRevision` and `conflictObject` were already carried on every placement.

## Refusals

- **The exceptions** (SYNC §4, D34 of the plan): a 401, a 407, a 423 and a quota exceeded are no refusal. rust/src/error.rs `names_the_request` already left out 401, 407, 408, 409, 412, 423, 425 and 429, and each connector's quota and rate reading (`graph_refuses`, `google_refuses`, `dav_refuses`, `throttled`, JMAP `overQuota`, IMAP `OVERQUOTA` outside the refusing codes) left quotas out, as of the 2026-11-16 and 2026-11-18 work. The Java engines read the bridge's flag alone (`PimdirEngine.refusal`), CalendarEngine also a 422, which names the request: they agree. No production line changed; the DAV case of `a_pim_refusal_is_told_by_its_status_and_never_by_a_quota` now also asserts 407 and 423.
- **The refused offered create**: io-pimdir 0.17's engine writes `UpsertPlacement` of the create before its `RefusePlacement`. `PimdirStorage.applyWrites` records every refusal after the whole batch's upserts and stamps, so the binding the upsert writes, the source's pending create under its provisional handle with no base, is there when `refuse_binding` runs. Sync vector 171 passes through the app's load and write with no change.
- **A queued move into its own collection parks**: the app's queue holds submissions alone, so no queued move reaches the owner; a staged move into the message's own mailbox is refused by the engine's mutation (`MoveInPlace`, since 0.16), which vector 170 shows through the bridge's mutate export, and the UI stages none.

## io-jmap 0.10.1

`Identity/set` now reads the identities it created and updated, `Calendar/set` is new and `JmapCalendarEvent::to_ical` converts an event to iCalendar; the `ical` feature now enables ical-rs's `parser`. None reaches an app path: the app reads identities by `Identity/get` alone (rust/src/mail/jmap.rs) and creates no calendar, and rust/src/calendar/jmap.rs `jmap_event` converts as `to_ical` does but sets the `@type` a server may leave out first, so it stays. No code changed.

## Tests

New, failing first:

- android/app/src/test/java/org/pimalaya/pimdir/PimdirStorageTest.java `aDeleteElsewhereProjectsATombstoneOverTheItemConflict`: a card the phone and the server changed differently, then deleted on the phone, loads for the server as a tombstone keeping its item conflict. Failed before the `statusOf` change: `expected:<[tombstone]> but was:<[conflict]>`.
- rust/src/offline/store.rs `a_refused_store_says_which_is_the_earlier`: failed on the old `check`: `left: "storage-draft-13" right: "storage-draft-13, earlier than this app's storage-draft-14"`.
- SyncVectorsTest at 0.17.0 first failed on 168 (`events: expected [{"handle":"a.vcf","kind":"Conflicted"}], got []`, the item left deleted) and 169 (`pushes: expected [{"kind":"Remove","handle":"b.vcf",...}], got []`, the item not retained), both passing after the `statusOf` change.

New, passing on the old code too: rust/src/offline/store.rs `the_version_vectors_order_as_the_refusal_says` (needs the new `ordered`), rust/src/search/tests.rs `index_versions_order_drafts_by_number_below_every_release` (needs the new `order`), and the search-draft-13 and search-v1 steps of `an_index_of_another_shape_is_refused_and_an_older_one_rebuilt`, which the old draft-only parse already answered alike.

Changed, for what the release correctly changed: the draft names in rust/src/offline/store.rs (the current store at draft-14; storage-draft-13 and sync-draft-13 refused), rust/src/ffi/offline.rs, rust/src/offline/shape/tests.rs, rust/src/search/tests.rs (search-draft-15 refused, search-draft-14 current), MailIndexTest, PimdirDbTest and PimdirStorageTest. The first cargo run on the new pin failed those draft-name tests, the fork's digest test (`left: Some("fbd4eacf9893aaf0") right: Some("0bb135b9cb8cc214")`) and the search tests reading a fresh index (`Rebuild { reason: "an index of search-draft-13" }`, the fork still writing draft-13), 12 in all.

The sync vectors at io-pimdir 0.17.0: 171 cases, 130 pass, 10 fail, 31 unbound (126, 10 and 31 of 167 at 0.16.0). The four new ones pass: 168 and 169 after the `statusOf` change, 170 and 171 as they came. The ledger android/app/src/test/resources/org/pimalaya/pimdir/sync-vectors.txt did not move.

## Evidence

Each as `nix develop --command <cmd>` from the repository root, `PIMALAYA_IO_PIMDIR_SPEC` at io-pimdir v0.17.0's spec/ (REVISION da7e01a, `git archive v0.17.0 spec` from the local io-pimdir clone into the scratchpad), on 2026-11-18:

- `cargo fmt --check`, `cargo clippy --all-targets --locked -- -D warnings` in rust/: clean.
- `cargo test --locked` in rust/: 740 passed, 1 ignored (737 before, three added).
- `gradle -p android :app:testDebugUnitTest :app:assembleDebug :app:assembleRelease --console=plain`: 1,037 tests, 5 skipped, none failing (1,036 before, one added; the first runs on the new pin failed the draft-name tests and SyncVectorsTest on 168 and 169 alone); the debug and release APKs built.
- `cairn/verify.sh`: conformant.

Nothing was installed on a device, no live suite was run and nothing connected to an account.

## Left, and for the owner

- **The reinstall screen** says "an earlier version stored" whatever the order; a later store (a downgrade) now names itself later in the reason line alone. Wording the screen by the order, and never offering to delete a later store, is task V4's.
- **The pending stamp of STORAGE §4.5 and §17** (io-pimdir's a-page-lands-in-few-statements) is not in 0.17.0's format; it lands or is dropped before v1. The SEARCH §7 instance bound is in draft-14 (io-pimdir `search::occurrence::INSTANCES_MAX`, 10,000,000) and reaches the app's index through the refresh.
- **A device check**: a draft-13 store opens on the reinstall screen with its reason; a fresh install creates the draft-14 store and index on Android 8's SQLite; a delete over a conflicted card reaches the other source.

## Capabilities moved

- offline-store (cairn/spec/offline-store.md): modified "The store's shape follows the canonical schema" (draft-14), "A store of another draft is not opened" (draft-14, the order in the refusal, a later store's scenario), "A store the previous build wrote" scenario, "The index is the app's own shape over FTS4" (draft-14, the version order), "A placement's status is derived from the row" (tombstone first, a scenario), "A change refused as it stands is held" (the exceptions, the offered create, two scenarios) and "The sync vectors run through the app's load and write" (the pin and the counts).
