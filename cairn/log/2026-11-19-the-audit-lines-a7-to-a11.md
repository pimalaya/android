---
cairn: log
change: the-audit-lines-a7-to-a11
date: 2026-11-19
---

# The audit lines A7 to A11

Lines A7 to A11 of pimdir's audit of 2026-11-12 (pimdir cairn/changes/audit-2026-11-12/tasks.md), on io-pimdir 0.17.0 and pimdir draft-14 as the app pinned them (rust/Cargo.toml, no pin moved). A8 to A11 are the ten cases the sync vector runner listed `fails` (cairn/log/2026-11-13-sync-vectors-run-through-the-java-store.md); each failed in the baseline run of SyncVectorsTest on 2026-11-19, with the messages that log quotes, and passes now. The change: cairn/changes/archive/the-audit-lines-a7-to-a11.

## A7: the writer's sync level is read back

java/pimdir/PimdirDb.java `onOpen` still compiles `SYNCHRONOUS_FULL` on the writer, then reads `PRAGMA synchronous` back inside a transaction, which runs on the writer (new app statement rust/pimdir/queries/app/synchronous.sql). Below 2 it runs the pragma again with `execSQL`, outside a transaction, and reads it again; still below, it throws `PimdirDb.SyncRefused`, an `IllegalStateException` ("The pimdir store's writer syncs at level N, below FULL"). On a SQLite applying the pragma as it compiles it, which is every one known, nothing more runs and no reader opens.

Refusing the store there is the owner's ruling of 2026-11-19. Thrown from `onOpen`, it would have reached whichever caller first opened the writer, a screen or a sync job, as a crash. So `App.refusal`, which every entry point checks before it uses the store (MainActivity, BackgroundJob, the sync adapters, ZoneChange), now opens the writer once after the file's own check (`syncRefusal`, a helper closed again) and records a refused open as the refusal `store-sync`. The app then stands aside on StoreRefusedActivity, as for a store of another draft, its title and message worded for this case: the phone cannot keep the data safely, nothing was read or changed, reinstalling does not help, report the model and the reason shown (strings `store_sync_refused_title` and `store_sync_refused_message`, English and French). `App.pimdir` refuses as for any refusal. The cost is one open of the writer on the thread making the first check, MainActivity's being the main thread, on top of the file check already read there.

Tests: PimdirDbTest `aWriterLeftBelowFullIsSetAgain` and `aWriterThatNeverSyncsAtFullIsRefused`, over a knob of the host SQLite shadow (ShadowHostSqliteConnection `ignoreSynchronousFull`) preparing the next statements setting FULL as one doing nothing, the platform's default set to NORMAL. Against the old `onOpen`: `FULL expected:<2> but was:<1>` and `opened below FULL`. StoreRefusalTest `aWriterNeverSyncingAtFullBlocksTheStoreReadably`: the refusal is `store-sync`, `App.pimdir` refuses, and the refused-store screen shows the sync title. Without `syncRefusal` it failed on `assertTrue(app.storeRefused())` (`AssertionError`), the store being refused only at its first open.

## A8: a body a policy did not adopt is kept

rust/src/offline/merge.rs answers `lost` beside the merge: the body io-pimdir's hub verdict leaves no item holding and its side, read off the facts `PimdirHub::reconcile_content` reads (a fill settling an item conflict by policy, a divergence of two read bodies under `prefer-incoming` or `prefer-existing`, a pull over a body its base never held), none after a person's settle. The hub keeps its own `overruled` record to the crate (`pub(crate)`), as it keeps `diverged`, which the bridge already reads off the facts the same way.

`PimdirStorage.applyUpsert` keeps it through `setAside`: `keepOverruled`'s retained item keyed `dup:<link>#<handle>` (minted again over a held key, a public id of its own, deleted, retained by the source), the item's flags as the upsert left them, its sort key and summary by side: the held summary row copied (new app statement copy_summary.sql, every Annex A table), the upsert's, or the one its body derives (`PimdirSql.derive`); the effect `overruled` reaches `Written.overruled`, so the engine reports `Overruled`. An `overrule` op's kept bodies now take the summary their body derives too, as io-pimdir's write does; no vector states one.

Vectors 85, 86, 90, 91, 104, 114 and 115 pass.

## A9: a retained row revived at meta sets its body aside

`HELD_ITEM` also answers the sort key and whether the row is retained. An upsert carrying no body that finds a retained row of a mutable kind merges as a fresh item, so the row revives holding no body at the level the upsert states, and its retained body is set aside by `setAside` as above, summary copied from the row before the listing's is written over it. A message's revival is unchanged: it keeps its retained body, one identity being one message.

Vector 108 passes: the revived row at level 1 with no body and the listed size 23, the retained body n1 under `dup:00000042oc1nst4nce#00000042oc1nst4nce` (seq 3) with size 15, `Overruled` after `Added`, nothing pushed.

## A10: a create's origin follows origin_for_link

rust/pimdir/queries/app/bound_elsewhere.sql no longer leaves out a holder whose item is a tombstone (ordered after a live one) and reads the account and kind from the roster (`collections.account`, compared with `IS`, and `collections.kind`), as card_bound_elsewhere.sql does, instead of the collection id's prefix. `PimdirStorage.originOf` binds it by `cardWide`. A move's target still pushes nothing of its own (MailEngine `pushAdd` returns early on a move source), so a tombstoned origin makes a copy only of a create that is no move.

Vector 49 passes: the `Add` from INBOX 10, accepted as 4.

## A11: the load carries what a held body told

The three load statements (rust/pimdir/queries/app/load_placements*.sql) add `mail_summary.size`, `attachment` and `invitation` and the `file_summary` row. `PimdirStorage.placementOf` hands a message's three beside its date, subject and sender, and a file's summary whole; the wire (rust/src/offline/wire.rs) builds the message's partial summary with them. The driver's `Dated` now records every summary a load carried and strips one a write hands back unchanged, as it did the partial one; a summary matched through a create's origin is stripped for mail alone.

Vector 52 passes (size 299 and attachment 0 kept over the listed 312); vector 58 passed before and still does.

## Evidence

Each as `nix develop --command <cmd>` from the repository root, `PIMALAYA_IO_PIMDIR_SPEC` at io-pimdir v0.17.0's spec/ (`git archive v0.17.0 spec` from the local clone into the scratchpad), on 2026-11-19:

- SyncVectorsTest: before, 171 cases, 130 passed, 10 failed (the listed ones), 31 unbound; after, 140 passed, none failed, 31 unbound, and the ten `fails` lines taken off android/app/src/test/resources/org/pimalaya/pimdir/sync-vectors.txt.
- `cargo fmt`, `cargo test --locked` in rust/: 741 passed, 1 ignored.
- `cargo fmt --check`, `cargo clippy --all-targets --locked -- -D warnings` in rust/: clean.
- `gradle -p android :app:testDebugUnitTest :client:testDebugUnitTest :app:assembleDebug --console=plain`: 1,047 and 14 tests, 5 skipped, none failing (the working tree also held other uncommitted work, its tests among them); the debug APK built. No JNI signature changed, so `assembleRelease` was not run.
- `cairn/verify.sh`: conformant.

Nothing was installed on a device, no live suite was run and nothing connected to an account.

## Left

- A device check: a fresh install opens its store on the device's own SQLite, the read-back logging nothing new.
- io-pimdir could serve its hub's `overruled` and `diverged` records, which the bridge would then read rather than restate.

## Capabilities moved

- offline-store (cairn/spec/offline-store.md): added "A body no item keeps is kept retained"; modified "A load carries each message's date" (the body's columns, a file's row, a scenario), "The store's writer syncs every commit" (the read-back, a scenario) and "The sync vectors run through the app's load and write" (140 pass, none fails).
- mail (cairn/spec/mail.md): modified "A link id names a message across mailboxes only where the account lists them as one" (a create's origin by the roster, a staged removal's holder counting).
