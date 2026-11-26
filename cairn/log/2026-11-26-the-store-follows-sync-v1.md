---
cairn: log
change: the-store-follows-sync-v1
landed: 2026-11-26
---

# The Java store follows sync-v1

The Java owner (android/app/src/main/java/org/pimalaya/pimdir/PimdirStorage.java, Stager.java) ports io-pimdir 0.20.3 to 1.0.0's owner fixes, read from io-pimdir v1.0.0 (fd5a437): `client/write.rs` (`revive_item`, `apply`, `minted_siblings`, `release_shared`, `apply_staged`), `hub.rs` (`release_shared`, `releases`, `same_content`, `shared_before`) and `client/run.rs` (`mutate`, `Staging`). The ten sync vectors that departed after cairn/log/2026-11-26-the-app-follows-pimdir-v1.md pass, and sync-vectors.txt gained no line.

## T1: a retained body keeps its own sort key (vectors 114, 115, 232)

- A bodiless revive of a mutable kind sets the retained body aside under the retained row's own sort key (`retained_item`'s), never the reviving member's (`applyUpsert`).
- A body kept apart with no summary of its own (`setAside`, `overrule`) takes the summary and the sort key its bytes give, as io-pimdir derives both for an overruled body holding none (`derived`, `sortKeyOf`).
- A fill adopting the diverging body nobody summarized (prefer-incoming) derives the item's summary and sort key from that body: the bridge's merge answers the hub's `unsummarized` verdict (rust/src/offline/merge.rs `MergedJson::unsummarized`).

## T2: a revive carrying another body adopts it (vector 233)

A retained row takes no part in the cross-source merge, io-pimdir's hub reading live items only (`load_items` excludes retained rows): a member carrying a body revives the row holding that body, the retained one released, never a `Conflicted` event nor a body kept apart.

## T3: two members of one Message-ID share no body (vectors 222, 223)

- A `Handles` load of a mail collection reads, beside the members it names, every other live member minted from their bare keys (`list_minted_links`, `mintedSiblings`), so an upgrade links no body by agreement into a mailbox holding two members stating one `Message-ID`.
- A write batch giving a minted member a body the member under its bare key holds releases it from that one, back to Meta, unless the batch wrote it, it needs the body as a release keeps it, a source states one server object id for both, or another minted member held the body already (`releaseShared`, `releases`, `sameContent`, `sharedBefore`, `mintedFrom`). The new app statement rust/pimdir/queries/app/release_item_body.sql lets the item and its bindings' bases and shared bodies go of it; rust/pimdir/queries/app/item_bindings.sql reads each binding's `server_object_id` too.

## T4: a copy or a move stages its create under the source syncing the target (vectors 227 to 230)

- The wire mutation of a copy or a move states `targetSynced`, whether any source syncs the target (`collection_sources`), and the bridge passes it as `PimdirMutate::target_synced` (rust/src/offline/wire.rs, rust/src/offline.rs `mutating`): a target no source syncs is refused, `target-unsynced`, staging nothing.
- Where the placement's source does not sync the target, `Stager.mutateInto` has the store load the target and write its upserts as the first source syncing it, for the mutation's thread alone (`PimdirStorage.stageInto`, `unstage`, `sourceIn`), as io-pimdir's runner stages one (`Staging`): the create carries no origin, and the source's own tombstone there is revived under its handle.

## Tests touched

- MailMoveTest `aBodilessMoveIntoAMailboxNeverSyncedIsRefused`: the move into a mailbox no source syncs is refused as `target-unsynced` now, before the bodiless check; its assertion reads that refusal.
- MutationTest: a move's wire states `targetSynced`.
- SyncVectorsTest: a copy or a move states `targetSynced` and runs staged as `Stager.mutateInto` runs it.
- rust/src/offline/wire.rs and merge.rs tests: the new fields.

## Evidence

From the repository root through the flake: `cargo fmt`, `cargo clippy --locked --all-targets -- -D warnings` clean; `cargo test --locked` 749 passed, 1 ignored. SyncVectorsTest: 242 sync vectors, 184 passed, 0 failed, 58 unbound (T1 and T2 alone left 222, 223 and 227 to 230 departing). `gradle -p android :app:testDebugUnitTest`: 1,075 tests, 0 failed, 3 skipped (the first run failed MailMoveTest's refusal message alone). `gradle -p android :app:assembleDebug` green. Nothing was installed on a device and no account was reached.

## Left

- A move into a mailbox no source has synced is refused now, as SYNC §7 rules: deleting a message moves it into the account's trash (MailStager `stageDelete`), so a trash no pass ever synced refuses the delete. Whether the app registers its declared mailboxes as synced by their server (io-pimdir 1.0.0's `PimdirSourceStore::register`, STORAGE §15.6) is an owner decision, not taken here.

## Changelog

CHANGELOG.md gains a Fixed line under Unreleased for the body two messages of one Message-ID shared.

## Capabilities moved

- offline-store: "A copy or a move into a collection no source syncs is refused" added; the sync vectors' counts at io-pimdir 1.0.0 restated, none failing.
