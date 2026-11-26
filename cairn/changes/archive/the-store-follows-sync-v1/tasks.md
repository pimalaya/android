---
cairn: tasks
change: the-store-follows-sync-v1
---

# Tasks

Each task is done when its vectors pass in `nix develop --command gradle -p android :app:testDebugUnitTest --tests org.pimalaya.pimdir.SyncVectorsTest --console=plain`, with no line added to sync-vectors.txt.

- [x] T1: a revive keeps the retained body under its own sort key (read `retained_item`'s `sort_key`): vectors 114, 115, 232.
- [x] T2: a revive by a member carrying another body adopts it and releases the retained one: vector 233.
- [x] T3: the `Handles` load reads the minted members beside the bare one (`list_minted_links`), and a member under a minted key taking the bare one's body releases it from the bare one: vectors 222, 223.
- [x] T4: Java answers whether any source syncs a copy's or a move's target, the bridge passes it as `target_synced`, a refusal reads `target-unsynced`, and a create the run's source cannot stage is staged under the source syncing the target, with no origin: vectors 227, 228, 229, 230.
- [x] T5: the whole JVM suite green, cairn/spec/offline-store.md's vector counts restated, a log entry written.
