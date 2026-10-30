---
cairn: change
id: io-pimdir-draft-05
status: active
created: 2026-10-30
---

# Follow io-pimdir 2e4ab95: pimdir storage-, sync- and search-draft-05

## Why

The app pins io-pimdir at 8907303; 2e4ab95 (pushed, still 0.8.0) follows pimdir's draft-05 and is the base the search index (cairn/changes/lists-from-the-index) needs. Tried on 2026-10-30 and put back: the bump is not a pin move. The app runs io-pimdir without `client`, its owner being the Java port (`PimdirStorage`, `PimdirDb`) over the canonical SQL, so every store rule draft-05 added to io-pimdir's own `client` store has to be ported by hand, several of them about not losing data in a sync.

## What the bump breaks

At compile time, 7 errors, all on the JSON wire to Java (rust/src/offline.rs, rust/src/offline/wire.rs):

- `PimdirChangeKind::SetFlags { handle, add, remove }` replaces `{ handle, flags }`: a flag push is two delta sets, derived against what the source listed, else the base (SYNC §4). Every remote applying a flag change (IMAP, JMAP, Graph, Gmail) applies deltas instead of a whole set. Change keys are re-derived (`set-flags` folds add then remove), so a key stored before the bump names no change after it.
- `PimdirWriteOp::Overrule { collection, handle }`, a new op staged before an overruling edit's upsert: the diverging bodies the edit does not adopt (the binding's `conflict_object`, then the item's) are kept as retained items under `dup:<link>#<handle>` and reported `Overruled`; the write is refused as `conflict-unread` while the conflict holds a body nobody read (SYNC §7, §9).
- `PimdirMutation::Edit { if_match, overrule }`: an edit stating the body it was made over is refused once another write replaced it.
- `PimdirYield::WantsVerify` / `PimdirArg::Verified`: before a push the store reads every body a chunk uploads and checks it against its name, a missing or corrupt one withheld and counted (STORAGE §7).
- A write answers `PimdirArg::Written` (what the merge recorded beyond its ops) where it answered nothing.

Not seen by the compiler, owed by the Java owner all the same:

- `bindings.shared_object` pins the body it names (refcount, recount, collector) and moves only with a body the item settled on, never on a body-less upsert nor one leaving the item conflicted (STORAGE §5, SYNC §9). `PimdirStorage` never writes the column today.
- The schema: `collections.kind` closed with a CHECK, `collections.role` open (no CHECK), `items.level` 1 or 2, `bindings.shared_object REFERENCES objects(hash)`, `store_meta.derivation`, the `collections_keep_queued` trigger, `bindings_by_shared_object`. `PimdirDb.reconcileDraftShape` adds and drops columns and rebuilds `collections` for one case; STORAGE §6 (io-pimdir src/client/schema.rs `reconcile`) rebuilds every table whose canonical text differs, inside one transaction with foreign keys off, after `repair_shared_objects`, `repair_levels`, `repair_kinds` and a recount, then backfills (`backfill_shared_object`, `backfill_invitation`, `backfill_task_organisation`), recounts and checks foreign keys. Without the rebuild a phone's store keeps the old constraints and no foreign key on `shared_object`.
- `INIT_STORE_META` takes `:derivation`; `set_store_version` replaces the hand-written version update in `PimdirDb.migrate`; `set_derivation` after open.
- A stated identity beginning with a reserved prefix (`alt:`, `hash:`, `dup:`, `part:`, `file:`, `esc:`) is keyed `esc:<hint>`, and a connector names the server identity with the one `esc:` stripped (`PimdirLinkId::stated`): every place the app derives a link id or names a resource after one (a calendar PUT named after its `UID`).
- A collection holding a queued action is not deleted (`PimdirError::Queued`, the trigger); a role outside its kind is refused by the writer, the schema no longer checking it.

The engine-side rules (the mass-absence guard, events reported after their write commits, the create landing under a body-less member) come with the crate and need only their new report fields read.

## How

One proposal-sized port, landed only with tests failing first against the old code:

1. The reconciliation as STORAGE §6 states it, in `PimdirDb`, tested on a store written at 8907303's shape (a fixture built by the old migrations) for every row kept, the canonical shape reached, and the refcounts recounted.
2. The wire and `PimdirStorage`: `shared_object` pinned and moved per SYNC §9, `Overrule`, `WantsVerify`, `Written`, `if_match`.
3. The remotes: flag deltas.
4. Link ids: `esc:` in and out.

Each step on the device before the next, as cairn/changes/body-loss-races and the 2026-06-24 revert taught.
