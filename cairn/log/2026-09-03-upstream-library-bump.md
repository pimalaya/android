---
cairn: log
change: upstream-library-bump
landed: 2026-09-03
---

# Take the released libraries, and the model changes they carry

Bumped ical-rs to 0.5, vcard-rs to 0.4, io-webdav to 0.3, io-pimdir to 0.4 and io-replica to 0.5, and dropped the `[patch.crates-io]` git checkouts of the last two. The mechanical half was the property markers moving from the syntax layer to the decoded model in both vcard-rs and ical-rs, `merge(base, left, right)` becoming `VcardMerge { base, left, right }.merge()`, `CarddavCardEnum` answering a `CarddavCardEnumOk`, and `WebdavSyncCollection::new` taking its options.

The rest was model, in three places.

The identity axis is gone: io-replica mints a second link id for a source's second copy of one identity, so the storage seam no longer freezes a placement and records the incoming handle beside the bound one. It refuses the repointing write instead, which is the complete answer now that the second copy is an item of its own, and `bindings.ambiguous_handles` goes with it.

A conflict now carries the body it diverged from. The engine records it on the binding and the upgrade pass fetches it, so the app stopped reading the remote a second time to capture it into the cross-source `items.conflict_object`. The hydrate pass asks for a conflict holding none, the resolution form reads its three documents from the store, and a resolution rebases the base onto the whole observed state rather than adopting its revision while discarding the body recorded beside it.

CardDAV enumerates through io-webdav's `PROPFIND` fallback where a server implements no `sync-collection`, so the app's own fallback (a REPORT such a server does not implement either) is retired with `enum_cards`.

The store now reconciles its shape against the canonical DDL rather than a transcribed list of folded columns, which is the list that had just gone stale.

Spec updated: `offline-store` (ADDED: the shape follows the canonical schema; MODIFIED: one identity under two handles), `conflicts` (ADDED: a conflict carries its diverging body, a resolution rebases onto the whole observed state), `carddav-sync` (MODIFIED: enumerated whatever the server implements).
