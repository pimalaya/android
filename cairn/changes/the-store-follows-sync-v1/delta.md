---
cairn: delta
change: the-store-follows-sync-v1
---

# Delta: the Java store follows sync-v1

Capability file: cairn/spec/offline-store.md.

## ADDED Requirements

### Requirement: A copy or a move into a collection no source syncs is refused
A copy or a move SHALL stage its create under the source syncing its target, the run's own where it syncs it (a server-side copy), another otherwise (no origin), and SHALL be refused with `target-unsynced`, staging nothing, when no source syncs the target (SYNC §7).

## MODIFIED Requirements

### Requirement: The sync vectors run through the app's load and write
The counts at io-pimdir 1.0.0 read 242 cases (format 11): 184 pass, none fails, 58 are unbound.

## REMOVED Requirements

None.
