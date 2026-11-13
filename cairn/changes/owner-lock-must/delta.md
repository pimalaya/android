---
cairn: delta
change: owner-lock-must
---

Folds into spec/offline-store.md once landed. What a refused lock shows is left open below, on the decisions of proposal.md.

## ADDED Requirements

### Requirement: The app owns its store under the standard's locks
The process SHALL hold an exclusive `flock(2)` lock on owner.lock, beside pimdir.db, from before it opens the store and for as long as it owns it (STORAGE §8), taken by the Rust core over the bridge, once per process and shared by every handle of it, never through `FileChannel`, whose locks are `fcntl` record locks. It SHALL NOT wait for the lock: a store another process holds is refused at once, naming the store, and neither read nor written. It SHALL open a lock file following no link, and SHALL never remove, rename nor replace one. Its collector SHALL hold the staging lock on objects.lock exclusively, taken without waiting.

#### Scenario: A second process on the store
- GIVEN owner.lock held with `flock` by another process
- WHEN the app starts
- THEN the store is refused at once, naming it, and nothing in it is read or written

#### Scenario: Two handles in one process
- GIVEN the app's store open
- WHEN the background job asks for it in the same process
- THEN it shares the process's lock, and nothing is refused

#### Scenario: A crashed owner
- GIVEN an app killed while it held owner.lock
- WHEN it starts again
- THEN it takes the lock, the file left behind locking nothing

## MODIFIED Requirements

### Requirement: Deleted items can be restored or purged
Its sentence "No orphan blob walk SHALL run, since the app's writers take no io-pimdir staging lock" is restated once the collector holds the staging lock: the walk is then sound, and stays unbuilt until a change of its own.

## REMOVED Requirements

None.
