---
cairn: change
id: body-loss-races
status: active
created: 2026-10-12
---

# Bodies survive a concurrent sync, and a message gone converges

## Why

On a Gmail API account, bodies of some messages (new mail, likely) went missing between syncs, and once a sync failed on a 404. The evidence was lost (app data cleared); reading the code found three causes.

1. **A sync writes back a body it never saw.** An io-pimdir pass loads its placements, merges, pushes over the network, then writes, the load and the write each taking `PimdirEngine.STORE` on their own. A body filed in between (the reader opening the message through `MailReader` and `PimdirItems.putObject`, or a fill chunk on another session) is overwritten by the write: `PimdirStorage.applyUpsert` sets `object_hash` and `level` from the stale placement (no object, meta), the refcount drops, the collector deletes the object and unlinks the blob. It happens whenever the message is in the pass's delta, which new Gmail mail is (`UNREAD`, `IMPORTANT`, `CATEGORY_*` label history right after delivery), or a staged change of it was pushed.
2. **A downloaded body is moved in outside any transaction.** `MailEngine.stored` adopts the file (`PimdirBlobs.adopt`) before the engine's write files its row (`storeObject` persisted, `ON CONFLICT DO NOTHING`, the file unchecked). If the file already exists, adopt keeps it and drops the new copy; a writer dropping the last reference to the same hash meanwhile has its collector unlink it: row present, file gone. And a file moved in while its hash sits in the static `COLLECTED` set is unlinked by any thread's next `unlinkCollected` before the row commits.
3. **A message gone fails the push.** The Gmail item writes (`set_gmail_flag`, `relocate_gmail_message`, `copy_gmail_message`, `destroy_gmail_message`) fail on a 404 for a message deleted since the listing (deleted for good, trash or spam purged, a draft saved under a new id), the failure aborts the mailbox's pass before its write, and `MailEngine.refused` (404, mailbox-refusal-skipped) reads it as the server refusing the mailbox.

## What

- **An upsert never takes away a held body.** In `applyUpsert`, for a mail item (immutable under its link id), a placement carrying no object at a lower level than the row holds, neither a tombstone nor a conflict, keeps the row's `object_hash` and `level`, and the binding keeps its `base_object` when it was that body. Releasing a body stays the job of `RELEASE_BEFORE`, `releaseCrossLinked` and the collectors.
- **A body filed ahead of its write keeps its file.** `PimdirItems.pin`/`unpin` count hashes in flight: `MailEngine.stored` pins before adopting, `download` releases once the upgrade (and so its write) has returned, deferring them for the collector so one no row names is unlinked. `unlinkCollected` checks and unlinks inside a transaction of its own, so no writer is between filing a body and committing its row, and skips and re-defers a pinned hash. `PimdirQueue.enqueue` files its body inside its transaction. A persisted `storeObject` whose file is missing is refused: the batch rolls back, the body stays owed.
- **A message gone converges.** The bridge answers an item write's 404 as done on Gmail (marker, trash, untrash, label move, delete) and Graph (marker, move, delete), and JMAP's `notFound` likewise; a copy of a message gone keeps its 404. `MailEngine`'s push takes a 404 from a write on one message as converged (remove, move, markers: accepted, a move's target create withdrawn) and refuses a copy, so a push never fails a pass with a 404 and `MailEngine.refused` is only fed by a mailbox's own reads.

## Library proposals (not done here)

- io-pimdir: a write precondition on the `changed` stamp (or the row's level and object), so an upsert merged from a placement loaded before a concurrent write is refused or rebased by the engine rather than guarded by the storage.
- io-pimdir: a persisted `StoreObject` that names its file, so the storage moves it in inside the write's transaction and no pin is needed.
- io-gmail: `GmailMessageModify` and friends exposing whether a 404 names the message or a label, so a label gone is not read as the message gone.
