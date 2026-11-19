---
cairn: change
id: merge-reads-the-hub-verdict
status: landed
created: 2026-11-19
---

# The merge reads the hub's verdict

## Why

rust/src/offline/merge.rs restates two parts of io-pimdir's cross-source merge (SYNC §9) that io-pimdir 0.17 keeps to the crate: whether an upsert recorded a divergence (`diverges`, read as `Written.conflicted`) and which body a policy did not adopt (`lost`, read as `Written.overruled`, added by cairn/changes/archive/the-audit-lines-a7-to-a11). Both are copies of `PimdirHub::reconcile_content`'s facts, so a rule io-pimdir changes there would drift from the app's unseen until a vector caught it.

The owner ruled on 2026-11-19 that io-pimdir will expose its reconcile outcome publicly. It is unreleased: this change is parked (status draft) until a release carries it.

## What

Once io-pimdir releases the public outcome and the app pins that release:

- `merge` reads `diverged` and the lost body (and its side) off the hub's own outcome of the upsert it absorbs, and `diverges` and `lost` are deleted.
- `MergedJson` keeps its wire shape (`diverged`, `lost`), so Java does not move.
- The merge.rs module header stops saying the two are read off the facts.
