---
cairn: change
id: owner-lock-must
status: abandoned
created: 2026-11-13
---

# The app takes the owner and staging locks

## Why

pimdir STORAGE §8 has an owner hold an exclusive advisory lock on owner.lock for as long as it owns the store, fail at once when it cannot take it, and take the staging lock on objects.lock exclusively around its collector (§5). The app states `storage-draft-10 owner` and takes neither: pimdir's own change of the same name (pimdir cairn/changes/owner-lock-must/tasks.md, its last line) and ledger line A4 of pimdir's audit of 2026-11-12 both leave it open.

The locks cannot be taken from Java. `FileChannel.lock` and `tryLock` are `fcntl` record locks on Linux and Android, which §8 forbids by name: they do not see a `flock` lock, and the process loses them when it closes any descriptor of the file. They are taken with `flock(2)` from the Rust core, over the bridge, as the no-replace rename of the blob store already is.

This proposal is not landed. The mechanism below needs no decision, but what the app does when a lock is refused does, and that is the owner's (Decisions, below). Nothing of it is in the tree.

## What

- **The core** (rust/src/offline/locks.rs, new): a registry of the owner locks the process holds, by canonical store directory, as io-pimdir's `PimdirLock` keeps one (io-pimdir src/client/lock.rs). Taking one opens owner.lock (`O_CREAT | O_RDWR | O_NOFOLLOW | O_CLOEXEC`, mode `0600`) and calls `flock(LOCK_EX | LOCK_NB)` through `libc`, already a dependency. A second take in the same process shares the first and counts it: the rule is about processes, and the host tests open a store many times in one JVM. `EWOULDBLOCK` is a refusal naming the store, a link at the path another (STORAGE §18).
- **The check before a write** (§8, a SHOULD): `fstat` on the held descriptor and `lstat` on the path answering one device and one inode, the store given up for the rest of the process once they differ, as `PimdirLock::verify` does.
- **The staging lock**: objects.lock opened the same way and locked with `flock(LOCK_EX | LOCK_NB)` for the collector's run, released by closing it.
- **The bridge**: `Native.pimdirOwn(dir)` answering `{}` or `{"refused": code, "detail"}` with io-pimdir's codes (`store-owned`, `store-exposed`), `Native.pimdirOwned(dir)` for the check (`lock-replaced`), `Native.pimdirCollect(dir)` and its release (`store-staging`).
- **Java**: `App.pimdir()` takes the owner lock after the draft check and before `new PimdirDb`, once per process; `PimdirDb` holds it and runs the check where a write begins; the collector takes the staging lock.
- **Tests**: in Rust, a second description of owner.lock refused while the first is held and taken once it is closed, a second take in one process shared, a lock file replaced under its holder refused from then on, a link refused; over the bridge on the host, a store opened twice in one JVM, and a store whose owner.lock a child process holds with `flock` refused.

## Decisions for the owner

1. **A store another process owns.** §8 says to fail at once, naming the store, and leaves what happens next to the caller. On Android the app is one process (the manifest names no `android:process`), so the case is a second process of the same user id: a `run-as` shell in a debug build, an instrumentation, a component later given a process of its own. The screen the app has for a store it does not open (StoreRefusedActivity, `store_refused_message`) tells the user to uninstall without keeping the data, which is wrong here: the store is sound and somebody is writing it. Three behaviours are possible, and the text of the second is the owner's, in two languages:
   - an exception naming the store where it is opened, so the activity crashes and a background run fails, nothing new on screen;
   - a screen of its own saying the app's data is in use and to close the other instance, with no advice to reinstall;
   - the background job, the sync adapters and the time-zone receiver doing nothing, as they do for a refused store, whichever the activity does.
2. **A lock file replaced under the app.** The check is a SHOULD. A running app that fails it writes nothing more: every later write throws. Whether that is checked at all, and what the user is told when a save or a sync stops so, is undecided. The app's files are private, so only the app or a `run-as` shell can replace the file.
3. **Where the collector's lock sits.** The app has no single collector: garbage rows are deleted inside ordinary write transactions (`PimdirItems.collectGarbage`) and their files unlinked once the transaction is over (`unlinkCollected`), a purge and the temporaries sweep beside them. Either every write that collects takes the staging lock without waiting and leaves its garbage for the next when refused, or collecting moves out of the writes into one operation, which is what §5 asks ("a write MUST NOT delete a row at refcount zero") and a larger change than this one. No process but the app can hold objects.lock on Android, so the lock is uncontended either way.
4. **When the owner lock is released.** For the life of the process, never released, or with the last `PimdirDb` closed. The app never closes its store; the host tests do.

## To check on a device

`flock(2)` is assumed available to an app on every supported level (API 26 and later): bionic exports it and the app's system call filter is built from bionic's own calls. The host tests cannot show it. One start on an API 26 device or emulator, and one on a current one, with owner.lock and objects.lock created under files/pimdir/ and no `SIGSYS`, settles it. A blocked call would end the process rather than fail, as `renameat2` does below API 30 (STORAGE §5's note), so this is checked before the lock ships, not after.

## Not in this change

The orphan walk of STORAGE §5 step 3, which the staging lock makes sound and nothing builds yet; an owner epoch, which the standard defers past version 1.

## Decided

Abandoned on 2026-11-13 by the owner: the app takes no lock at all. Its store lies in the app's private directory, which no other user id opens, and the app is one process, its services and its background job included, so a lock on the open file would exclude nobody. What can overlap is inside that process, where `flock(2)` sees nothing and the app's own locks answer: one sync at a time, one pass per collection, one writer. The four decisions above ask nothing any more. The standard is not changed: the app stays short of the MUST of STORAGE §8 on this point, which its spec states (cairn/spec/offline-store.md, "The app takes no lock on its store"). A second process added to the app reopens this change.
