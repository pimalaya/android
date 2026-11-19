---
cairn: tasks
change: merge-reads-the-hub-verdict
---

# Tasks

- [x] Wait for an io-pimdir release exposing the reconcile outcome; pin it
- [x] Read `diverged` and `lost` from it in rust/src/offline/merge.rs; delete `diverges` and `lost`
- [x] SyncVectorsTest unchanged (140 pass at 0.17), merge.rs tests green
- [x] Log; no spec change expected (behaviour stays)
