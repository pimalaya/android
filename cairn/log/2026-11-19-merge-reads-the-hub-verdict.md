---
cairn: log
change: merge-reads-the-hub-verdict
date: 2026-11-19
---

# The merge reads the hub's verdict

io-pimdir 0.18.0 (released on crates.io on 2026-11-19, pinned by cairn/log/2026-11-19-the-app-follows-the-releases-of-2026-11-19.md) makes the cross-source merge of one upsert public: `hub::PimdirHubItem::reconcile` answers a `PimdirHubVerdict` (`diverged`, `kept_shared`, `lost`, `unsummarized`), `lost` a `PimdirHubLoser` with its `PimdirHubLoserSide` (`Shared`, `Incoming`, `Diverging`). The change: cairn/changes/archive/merge-reads-the-hub-verdict.

## What moved

rust/src/offline/merge.rs no longer restates the three facts. `merge` runs `reconcile` on a copy of the item as it was held, gated as `PimdirHub::absorb` gates it: no verdict for a tombstone, nor for a person's settle (a `Settle` before an upsert carrying a body over a conflicted item). `MergedJson.diverged` is the verdict's `diverged` and `MergedJson.lost` its loser, the side spelled `shared`, `incoming` or `diverging` as before. The functions `diverges` and `lost` are deleted, and the module header says the two are the hub's own verdict.

`MergedJson` keeps its wire shape, so java/pimdir/PimdirStorage.java does not move. `kept_shared` is still read off the hub's result (the item's body after the absorb differing from the upsert's), not off the verdict's `kept_shared`: the two agree for every upsert the Java owner hands the merge, and reading the verdict's would be a separate change.

The old `diverged && after.conflicted` guard is gone: a divergence always leaves the item conflicted, and the settle it excluded is now the gate before `reconcile`.

io-pimdir's hub keeps its `overruled`, `diverged` and `unsummarized` records `pub(crate)`; the bridge still builds a one-item `PimdirHub` and absorbs the batch's `Overrule`, `Settle` and upsert to read the body, the conflict and the binding's `shared_object`.

## Tests

Behaviour stays, so no test was added: the merge.rs tests pass unchanged, and SyncVectorsTest, which runs the vectors stating a divergence and a policy loser (85, 86, 90, 91, 104, 108, 114, 115 among them) through the bridge's merge export, passes as at 0.17 plus vector 172.

## Evidence

Each as `nix develop --command <cmd>` from the repository root, `PIMALAYA_IO_PIMDIR_SPEC` at io-pimdir v0.18.0's spec/ (REVISION e188e31, `git archive v0.18.0 spec` from the local clone into the scratchpad), on 2026-11-19:

- `cargo fmt --check`, `cargo clippy --all-targets --locked -- -D warnings` in rust/: clean.
- `cargo test --locked` in rust/: 738 passed, 1 ignored.
- `gradle -p android :app:assembleDebug :app:testDebugUnitTest :client:testDebugUnitTest --console=plain`: 1,058 and 14 tests, 5 skipped, none failing; SyncVectorsTest 172 cases, 141 pass, none fails, 31 unbound. No JNI signature changed, so `assembleRelease` was not run.
- `cairn/verify.sh`: conformant.

Nothing was installed on a device and nothing connected to an account.

## Capabilities moved

None: the bridge reads the verdict it restated.
