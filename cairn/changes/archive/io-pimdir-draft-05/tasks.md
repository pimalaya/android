---
cairn: tasks
change: io-pimdir-draft-05
---

# Tasks

Closed on 2026-11-01 as abandoned, superseded: the port was folded into cairn/changes/archive/platform-sqlite step 2, which cairn/changes/archive/the-shape-follows-storage-draft-08 ran at draft-08 as steps 2a and 2b. The rules below landed there, but the reconciliation and the pin, which draft-08 replaced.

- [x] Try the bump (2026-10-30): 7 compile errors on the wire, store rules owed by the Java owner listed in the proposal; pin put back, tree green
- [x] Review the port's shape with the user (2026-10-31: folded into cairn/changes/archive/platform-sqlite step 2, the rules below written in the app's own shape rather than ported onto the canonical text; retargeted to draft-08 on 2026-11-01)
- [ ] `PimdirDb`: STORAGE §6 reconciliation (rebuild by canonical text, repairs, backfills, recount, foreign key check), `INIT_STORE_META` with `derivation`, `set_store_version`, `set_derivation`; a test over a store of 8907303's shape failing first
  - Not done, by design: draft-08 makes drafts exact (STORAGE §4.2), so the app reconciles nothing and refuses a store of another draft at start-up (reinstall), its reconciliation, backfills and repairs deleted at 2a. `INIT_STORE_META` takes `derivation` 3 at 2a; `set_store_version` and `set_derivation` have nothing to do with no migration run. The refusal is tested over an earlier draft's store (`aStoreOfAnEarlierDraftIsRefusedAndLeftAsItWas`).
- [x] Wire and `PimdirStorage`: `shared_object` pinned and moved (SYNC §9), `Overrule` with the retained `dup:` bodies and `conflict-unread`, `WantsVerify`, `Written`, `Edit.if_match`; tests failing first (2a, first and second passes: cairn/log/2026-11-01-the-shape-follows-storage-draft-08.md)
- [x] Remotes: `SetFlags` deltas on IMAP, JMAP, Graph, Gmail (2a connectors, checked on the device, `pushed=2`: the same log)
- [x] Link ids: `esc:` on derivation, stripped toward servers (2a: a CalDAV or CardDAV create pushed under its identity with `esc:` stripped, the same log)
- [ ] Pin moved to 2e4ab95; `:app:assembleDebug :app:testDebugUnitTest :app:assembleRelease` green; device checks per step
  - Not done: the pin moved to io-pimdir bff5737 (draft-08) instead, then 09d2d1c and fd0c060, green and checked on the device per step (the same log).
