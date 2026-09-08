---
cairn: change
id: upstream-library-bump
status: landed
created: 2026-09-02
---

# Take the released libraries, and the model changes they carry

## Why

The crate was pinned to ical-rs 0.2, vcard-rs 0.2, io-webdav 0.2, and to git checkouts of io-pimdir and io-replica. Everything since has been published: ical-rs 0.5.1, vcard-rs 0.4.0, io-webdav 0.3.0, io-pimdir 0.4.1, io-replica 0.5.0. Two of those releases change what the app has to model, rather than only where a type lives.

**The identity axis is gone.** io-replica 0.5 removed `ReplicaStatus::Ambiguous` and `ReplicaPlacement::ambiguous_handles`: a source holding one identity twice is no longer frozen, it gets a second item under a minted `dup:` key, so both copies are stored, listed and reconciled like any other member. The app's storage seam still froze, which now means the engine mints a key the store refuses to file.

**A conflict now carries its diverging body.** `ReplicaPlacement::conflict_object` records the remote body a conflict diverged from, beside the revision naming it, and the upgrade pass fetches it. The app captured that body itself, with a second read against the backend, into `items.conflict_object`, which is the cross-source column rather than the per-source one.

**A CardDAV server implementing no `sync-collection` can be enumerated.** io-webdav 0.3 answers `UnsupportedReport` and offers a `PROPFIND` Depth 1 fallback. The app fell back to a REPORT `addressbook-query` instead, which such a server does not implement either.

## What

Bump every dependency to its released version, drop the `[patch.crates-io]` section, and follow the four API moves (the vCard and iCalendar property markers to the decoded model, `VcardMerge`, `CarddavCardEnumOk`, `WebdavSyncCollectionOptions`).

Then follow the model changes:

- Drop the ambiguity freeze from the storage seam and refuse the repointing write instead, which is what the format asks for now that the second copy has an item of its own.
- Move the conflict's diverging body onto `bindings.conflict_object`, written by the engine through the placement wire and read back by the resolution form, so a conflict is settled with no credentials, no backend and no network.
- Let the `PROPFIND` fallback enumerate a server with no `sync-collection`, and retire the app's own fallback with it.
- Reconcile the store's shape against the canonical DDL rather than a transcribed list of folded columns, since that list is what drifted.
