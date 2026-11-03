---
cairn: log
change: the-bridge-follows-the-freeze-gaps
date: 2026-11-03
---

# The bridge follows the freeze gaps

io-pimdir moves from fd0c060 to 0c70144 (pimdir 6687cf4, storage-draft-08 and sync-draft-07 unbumped), which brings mime-rs and its codecs in place of mail-parser for the search extraction.

- **A bodiless copy or move states whether its source syncs the target** (offline-store). `PimdirMutate` now refuses a `Copy` or a `Move` of a placement holding no body unless `syncs_target` says the source syncs the target, `false` by default, so every trash move of an undownloaded message would have failed. `Stager.mutateMove` and `mutateCopy` read it through `PimdirStorage.syncsTarget` (the canonical `COLLECTION_SOURCES` for the target, the source of the collection staging it), `Mutation` carries it as `syncsTarget`, and the bridge hands it to the coroutine (rust/src/offline.rs, `MutationJson::syncs_target`). Guarded by MailMoveTest: its five bodiless move and copy tests failed with "holds neither a body nor a base to copy from" until the bridge stated it, their fixture now checkpointing Trash and Sent as a first sync would, and `aBodilessMoveIntoAMailboxNeverSyncedIsRefused`, which fails when the flag is always true.
- **A page may say its listing is capped** (`SnapshotJson.capped`, SYNC section 4 and 5). No connector of the app knows of a cap, so none sets it.

- **An edit over an unread base is hydrated** (offline-store). io-pimdir now holds back the `Update` of a local body over a base naming a revision and no body until an upgrade at `Full` reads that base (SYNC section 5), and the hydrate pass upgraded only what was below full, bodiless or awaiting a conflict's body, so such an edit (a card the phone wrote over one the server listed without its body) would have waited for good. rust/pimdir/queries/app/handles_below_full.sql now also selects it. Guarded by PimdirStorageTest `anEditOverAnUnreadBaseIsHydrated`, which fails without the line.

`cargo test --all-features` 603 passed; `:app:assembleDebug` and `:app:testDebugUnitTest` green.
