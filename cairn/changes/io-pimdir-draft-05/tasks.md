---
cairn: tasks
change: io-pimdir-draft-05
---

# Tasks

- [x] Try the bump (2026-10-30): 7 compile errors on the wire, store rules owed by the Java owner listed in the proposal; pin put back, tree green
- [ ] Review the port's shape with the user (2026-10-31: folded into cairn/changes/platform-sqlite step 2, the rules below written in the app's own shape rather than ported onto the canonical text)
- [ ] `PimdirDb`: STORAGE §6 reconciliation (rebuild by canonical text, repairs, backfills, recount, foreign key check), `INIT_STORE_META` with `derivation`, `set_store_version`, `set_derivation`; a test over a store of 8907303's shape failing first
- [ ] Wire and `PimdirStorage`: `shared_object` pinned and moved (SYNC §9), `Overrule` with the retained `dup:` bodies and `conflict-unread`, `WantsVerify`, `Written`, `Edit.if_match`; tests failing first
- [ ] Remotes: `SetFlags` deltas on IMAP, JMAP, Graph, Gmail
- [ ] Link ids: `esc:` on derivation, stripped toward servers
- [ ] Pin moved to 2e4ab95; `:app:assembleDebug :app:testDebugUnitTest :app:assembleRelease` green; device checks per step
