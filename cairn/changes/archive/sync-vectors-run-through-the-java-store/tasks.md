---
cairn: tasks
change: sync-vectors-run-through-the-java-store
---

# Tasks

- [x] Bridge: the sync, upgrade and mutate coroutines built by functions the exports and the seam share (`OfflineSync::coroutine`, `upgrading`, `mutating`), `Driver::drive` over a borrowed coroutine.
- [x] Bridge: the debug-only seam (rust/src/ffi/vector.rs): `SyncVectors.sync`, `upgrade`, `mutate` answering events, failure and park; `SyncVectors.named`.
- [x] Runner: seed by the standard's statements and plain SQL, one store per case.
- [x] Runner: the scripted remote over `Stager` (pages, a refused cursor, an interruption, fetch answers, outcomes), the mutations by the app's `Mutation`.
- [x] Runner: compare pushes, events, `failed`, `parked`, rows, summaries, objects; every difference of a case told at once.
- [x] Ledger: sync-vectors.txt, `fails` and `unbound`, the suite failing on any departure.
- [x] Checked that the runner sees a difference: seven expectations altered in a scratch copy of the vectors (events, sources, bindings, `failed`, a push, a summary, an object), each reported.
- [x] Fix: `loadCollection` states `offered` (vectors 93 and 99 failed first).
- [x] Fix: a page not the last and handing no cursor crosses as it is (vector 79 failed first).
- [x] Spec folded (offline-store), log written.
- [ ] Left to the owner: the eleven `fails` of the ledger, and whether the unbound verbs are bound.
