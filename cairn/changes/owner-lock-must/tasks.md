---
cairn: tasks
change: owner-lock-must
---

# Tasks

Nothing is landed: the four decisions of proposal.md come first. The intent this follows is pimdir's own (pimdir cairn/changes/owner-lock-must/tasks.md, "Downstream, Android") and ledger line A4 of pimdir's audit of 2026-11-12, whose two other points landed (the `(own statement text)` suffix on 2026-11-13 with draft-10, the receipt on 2026-11-13 with cairn/log/2026-11-13-a-read-verifies-and-a-send-leaves-its-receipt.md).

- [ ] Owner: decide what the app does on a store another process owns (proposal.md, decision 1), with the screen's text in English and French if a screen it is.
- [ ] Owner: decide whether the lock file is checked before a write and what a failed check shows (decision 2).
- [ ] Owner: decide where the collector's staging lock sits (decision 3).
- [ ] Owner: decide when the owner lock is released (decision 4).
- [ ] Device: `flock(2)` reachable by the app on API 26 and on a current level, no `SIGSYS`.
- [ ] rust/src/offline/locks.rs: the registry, `flock(LOCK_EX | LOCK_NB)` on owner.lock, the `fstat` against `lstat` check, the staging lock, each with its test seen failing first.
- [ ] The bridge exports and their Java mirrors in the client module.
- [ ] `App.pimdir()` takes the owner lock before the store opens; the entry points follow decision 1.
- [ ] The collector takes the staging lock, by decision 3.
- [ ] cairn/spec/offline-store.md: fold delta.md, and restate "Deleted items can be restored or purged", which says the app's writers take no staging lock.
- [ ] Log entry; then the last line of pimdir cairn/changes/owner-lock-must/tasks.md and the lock point of A4 can be closed there.
