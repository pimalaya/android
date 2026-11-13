---
cairn: log
change: owner-lock-must
date: 2026-11-13
---

# The app takes no lock on its store

The last open point of line A4 of pimdir's audit of 2026-11-12, the owner and staging locks of STORAGE §8, is decided by the owner on 2026-11-13: the app takes neither. The proposal written the same day (cairn/changes/owner-lock-must) is abandoned, its four decisions with it, and nothing of the code moved.

The reason is the platform's. The store lies in the app's private directory, which no other user id opens, and the manifest names no process of its own for any service, receiver or provider, so the background job, the sync adapters and the open app are one process. An advisory lock belongs to the open file and excludes other processes alone: here it would exclude nobody, at the price of a system call a filter could refuse on an old release. What can overlap is two syncs inside the process, which the app already holds apart: `Locks.takeSync`, one permit, taken by the foreground passes (SyncCoordinator) and by BackgroundJob, which also stands down while the app is on screen; `Locks.collection`, one pass per collection; `Locks.store`, one writer.

The standard is not changed, by the owner's choice: the app stays short of the MUST of §8 on this point, and says so. Capability moved: offline-store ("The app takes no lock on its store" added, with what would void it: a component declared in a process of its own).

Left: nothing checks that the manifest stays at one process; a second one reopens the change.
