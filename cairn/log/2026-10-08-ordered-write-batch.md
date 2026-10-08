---
cairn: log
change: ordered-write-batch
landed: 2026-10-08
---

# The store applies a write batch in order

Capabilities moved: offline-store (added: a write batch applies in order).

**Ordering.** `PimdirStorage.applyWrites` held every drop to the end of a batch and skipped one whose handle an upsert of the same batch named. That came over from `CardStore` (5fb3a0d), whose rows were keyed by handle under io-offline: an accepted create could restate the handle it dropped, and arrival order deleted the row it had just written. Neither holds any more: a provisional handle is `U+0001` and the key, never an assigned one, io-pimdir writes the superseded drop before the upsert moving the binding, and its rekey never drops a handle it also upserts. io-pimdir's store folds a batch in order (src/client/write.rs, SYNC §10), and since c5e2c65 a `Remove` of a pending create writes `[UpsertPlacement(tombstone), DropPlacement { Deleted }]`, which the old store cancelled. Drops now apply where they stand; the stamps still follow the upserts. What io-pimdir decides from the hub the whole batch folded into is decided at the batch's end: a mail create a superseded drop released is removed unless an upsert of the batch carried it on (its summary is read by the landing upsert after the drop), and an item the batch created and left unbound is not stored. An unnamed upsert of a handle the batch dropped takes the identity the handle held, as io-pimdir names it against the store the batch began from.

**Bridge.** The `withdrawals` workaround in rust/src/offline.rs, which wrote a removal's tombstones with no base as a second drop batch, is gone: io-pimdir withdraws the create in the same write.

**Destination.** A mail tombstone's origin is read with the canonical `DESTINATION_FOR_LINK` and names the pending create's handle, which io-pimdir now reads to tell a relocation into a minted create (no `linkId` on the `Remove`) from one into the identity's own.

rust/Cargo.toml patches io-pimdir to the local checkout until its commits are pushed.
