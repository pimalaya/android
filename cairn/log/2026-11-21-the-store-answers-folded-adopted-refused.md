---
cairn: log
change: the-store-answers-folded-adopted-refused
date: 2026-11-21
---

# The store answers folded, adopted and refused

The Java half of cairn/log/2026-11-20-the-bridge-follows-io-pimdir-0-19.md: the store answers the three fields io-pimdir 0.19.0 added to the load and write replies, the sync vectors run at format 11, and the Java tests and comments state draft-15. A follow-up of a dependency move, no proposal.

## Load: `folded`

- rust/pimdir/queries/app/load_placements.sql, load_placements_by_link.sql and load_placements_by_handle.sql (the app's own statements) read the binding's `shared_object` as a 28th column.
- android/app/src/main/java/org/pimalaya/pimdir/PimdirStorage.java `loadCollection` reports in `folded` each placement of a mutable kind, bound with a base, whose item holds a body other than the binding's `shared_object` (its base's body until it folded once), the rule of `PimdirHub::folded`. A message is left out: the engine reads `folded` on the content axis alone, which a message has none of, and the body a reader stored would name every opened message.
- A server edit meeting a phone edit the store had folded in no longer reads as the server binding's own conflict: it is the collection's divergence, settled by its policy (SYNC §5, §9).

## Load: no offer under a `hash:` key

`loadCollection` projects no create for an unbound item under a `hash:` key, which asserts no identity another source could hold (SYNC §9, as io-pimdir's `created_placement`). Sync vector 191 found it.

## Write: `adopted` and `refused`

PimdirStorage's upsert path turns the hub's lost body into an effect: side `shared` (the policy took the incoming body over the item's) is an `adopted` effect, side `incoming` (the policy kept the item's) a `refused` one. `PimdirStorage.written` lists both in the `Written` reply. Every write answering either also answers `overruled`, so the `applied` listeners see no new batch.

## A binding conflict over the item's ancestor

Answering `folded` exposed a loss, which CalendarMirrorTest anEditBothSidesMadeStaysConflictedForTheTriage caught: the phone pass pulling a phone edit over a server edit already folded in records the item's conflict and moves the phone binding's base onto the phone's body; the phone row's revision then moves from its dirty fingerprint to its stamp, the next pass records that as a phone binding conflict whose diverging body is the item's, and the triage merged it over the binding's base, the phone's own body, so the phone's edit read as no change and the resolution, naming the item's diverging body, settled both conflicts on the server's body. `loadConflicts` now gives such a binding conflict (its diverging body the item's) the item conflict's ancestor (`ancestorOf`, the body the disagreeing source last agreed with) as its base; rust/pimdir/queries/app/binding_conflicts.sql reads the item's `conflicted` and `conflict_object` for it. The phone's edit now stays conflicted for the form.

The phone spoke's revision moving once after it stamps a row it listed dirty is left as it is: a pull reads it as one more change of the same body.

## Sync vectors at format 11

- android/app/src/test/java/org/pimalaya/pimdir/SyncVectorsTest.java reads format 11 and compares a run's `unlisted`, which rust/src/ffi/vector.rs now answers from the sync report.
- android/app/src/test/resources/org/pimalaya/pimdir/sync-vectors.txt lists seven more cases unbound: 174, 175, 181, 182, 183 and 194 (the bridge syncs under the manual policy alone) and 190 (no rekey).

## Draft-15 and the libraries' output

- PimdirDb.java's class comment, the SYNC §3 citation in PimdirStorage.java, and the assertions of MailIndexTest (search-draft-15), PimdirDbTest (storage-draft-15) and PimdirStorageTest (sync-draft-15) moved.
- android/app/src/test/resources/org/pimalaya/contact/contact-form.txt and mapping-corpus.txt take vcard-rs 0.6.1's `GENDER` with no identity, written without a trailing `;`: every changed line is a card or a form holding a bare `GENDER` (corpus card 13, form model 7).

## Evidence

2026-11-21, with `PIMALAYA_IO_PIMDIR_SPEC` at the io-pimdir v0.19.0 working tree's spec/ (the published crate leaves the vectors out): `nix develop --command bash -c 'cd rust && cargo test --locked'`, 738 passed, 1 ignored, `cargo fmt --check` and `cargo clippy --locked --all-targets` clean; `nix develop --command gradle -p android :app:testDebugUnitTest --console=plain`, 1064 tests, 0 failed, 5 skipped. PimdirStorageTest gains aBodyAnotherSourceFoldedInLoadsAsFolded and aPolicyReportsTheBodyItAdoptedOrRefused, which the code before fails (no `folded`, no `adopted`).

## Capabilities moved

- offline-store (cairn/spec/offline-store.md): a load reports `folded` and offers nothing under a `hash:` key, a write reports `adopted` and `refused`, the vectors run at format 11.
- conflicts (cairn/spec/conflicts.md): a binding conflict whose diverging body is its item's merges over the item conflict's ancestor.
