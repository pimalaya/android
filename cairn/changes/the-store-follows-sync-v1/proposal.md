---
cairn: change
id: the-store-follows-sync-v1
status: active
created: 2026-11-26
---

# The Java store follows sync-v1

## Why

The bridge moved onto io-pimdir 1.0.0 (pimdir storage-v1.0.0, sync-v1.0.0, search-v1.0.0) on 2026-11-26 (cairn/log/2026-11-26-the-app-follows-pimdir-v1.md). io-pimdir's engine runs over the app's own load and write, java/pimdir/PimdirStorage.java and java/pimdir/Stager.java, which re-implement what io-pimdir's `client` does for its own owner. io-pimdir 0.20.3 to 1.0.0 fixed that owner half in ways the Java half does not follow yet, and SyncVectorsTest reports ten sync vectors departing at the v1 spec (fd5a437, REVISION 47cc08a), none listed: sync-vectors.txt says its `fails` lines only go, so they are ported, never listed.

## What

Port to the Java owner, each case passing through SyncVectorsTest:

- **A retained body keeps its own sort key** (io-pimdir 1.0.0 "a bodiless revive giving the retained body the reviving member's sort key"; `retained_item` now answers `sort_key`): vectors 114, 115 and 232.
- **A revive by a member carrying another body adopts it and releases the retained one**: vector 233 (the app reports a `Conflicted` event and keeps the retained body).
- **Two members of one `Message-ID` share no body** (io-pimdir 0.20.3: the `Handles` load reads the minted members beside the bare one, `list_minted_links`; a `Full` upgrade links no body by agreement into a collection holding another member stating the hint; the write giving a minted member the body the bare one holds releases it, `PimdirHub::release_shared`): vectors 222 and 223.
- **A copy or a move into a collection another source syncs stages its create under that source, with no origin, and one into a collection no source syncs is refused** (`PimdirMutate::target_synced`, `PimdirMutateError::Unsynced`, `target-unsynced`, SYNC §7): vectors 227, 228, 229 and 230. The bridge's `mutating` takes `syncsTarget` alone today; Java answers neither whether any source syncs the target nor stages under another source.

Until this lands the app's `sync-v1.0.0 engine` statement stands short of SYNC §12 by these ten cases.
