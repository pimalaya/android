---
cairn: change
id: the-audit-lines-a7-to-a11
status: landed
created: 2026-11-19
---

# The audit lines A7 to A11

## Why

pimdir's audit of 2026-11-12 (pimdir cairn/changes/audit-2026-11-12/tasks.md) leaves five lines on the app before pimdir v1, four of them found by the sync vector runner (cairn/log/2026-11-13-sync-vectors-run-through-the-java-store.md), ten cases of which failed at io-pimdir 0.17.0:

- **A7**: `synchronous = FULL` is compiled on the writer and never read back, so a device whose SQLite did not apply it writes below STORAGE §4.1 unseen.
- **A8**: the Java write keeps no body a collection's policy did not adopt (SYNC §9, STORAGE §11): under `prefer-incoming` or `prefer-existing`, no retained item, no `Overruled` (vectors 85, 86, 90, 91, 104, 114, 115).
- **A9**: a retained row a listing revives at meta keeps its retained body as the item's (STORAGE §11.1, vector 108), so it projects dirty and pushes an update over a body nobody read.
- **A10**: the app's `bound_elsewhere` leaves out an origin a tombstone holds and reads the account off the collection id's prefix (SYNC §3, vector 49).
- **A11**: the load carries a message's date, subject and sender alone (SYNC §6, Annex A.1, A.7, vectors 52, 58), so a listing's summary overwrites the size and mark a held body told.

## What

- A7: java/pimdir/PimdirDb.java reads the level back on the writer; below FULL it runs the pragma again outside a transaction, then refuses the store (`PimdirDb.SyncRefused`) if it is still below. The refusal is the owner's ruling of 2026-11-19. It reaches the user as the refused-store screen, worded for this case (`store-sync`), never as a crash: `App.refusal` opens the writer once before any entry point uses the store.
- A8: rust/src/offline/merge.rs answers the body the hub's verdict did not adopt (`lost`, with its side), read off the same facts as `PimdirHub::reconcile_content`; `PimdirStorage.applyUpsert` keeps it as a retained item keyed `dup:<link>#<handle>`, with the item's flags and the summary of its side (the held row copied, the upsert's, or derived from the body), and reports it in `Written.overruled`. An overrule op's kept bodies take a summary derived from their body too, as io-pimdir's write does.
- A9: an upsert carrying no body that revives a retained row of a mutable kind merges as a fresh item, the row reviving at the level the upsert states with no body, and its retained body is kept apart the same way.
- A10: bound_elsewhere.sql follows `origin_for_link`: a tombstone's binding counts, a live one first, the account and kind read from the roster as card_bound_elsewhere.sql does.
- A11: the load statements add a message's size, attachment mark and invitation and a file's summary row; the wire hands them to the engine, and the driver strips any loaded summary a write hands back unchanged.
