---
cairn: log
change: sync-vectors-run-through-the-java-store
date: 2026-11-13
---

# The sync vectors run through the Java store

Line A2 of pimdir's audit of 2026-11-12: the app ran none of the sync vectors (SYNC §11) through its Java load and write. It runs them now, in a host JVM test on Android 8.0's SQLite 3.18.2 (android/app/src/test/java/org/pimalaya/pimdir/SyncVectorsTest.java). At io-pimdir cd33f0e, 158 cases: 117 pass, 11 fail, 30 are unbound, each of the 41 listed with its reason in android/app/src/test/resources/org/pimalaya/pimdir/sync-vectors.txt, any departure from which fails the suite.

## The runner

- **Seeds** one store per case, a `PimdirDb` over a directory of the case's own, by the standard's statements served over the bridge and run by `PimdirDb.exec` (`set_collection_kind`, `store_object`, `insert_item`, `retain_item`, `insert_binding`, `supersede_item`, `add_reference`, `upsert_checkpoint`, `recompute_refcounts`), and by plain SQL where io-pimdir's own runner writes plain SQL (flag rows, the policy and generation of a collection, a binding's round, a source's round and coverage, summary rows and their lists). Bodies go in through `PimdirBlobs.put`, one stated `blob` false unlinked and one stated `corrupt` given another byte. Nothing is seeded through `PimdirStorage.applyWrites`, so the write under test prepares none of its input, and every row the engine reads comes out of `PimdirStorage.loadCollection`.
- **Names the run's source `server`**, the one name the app syncs a server under (`PimdirStorage.sourceOf`), and every other source of the case with a prefix keeping its order around it (`0-` before, `z-` after), mapped back when rows are read. Collection ids are kept: a push's key is derived from them.
- **Drives** the case through a driver extending `Stager`: `load`, `lookup`, `verify` and `write` are the app's own, a mail collection's lookup being `lookupMessages` under its account as the mail driver's is. `enumerate`, `fetch` and `push` are answered from the case in Java, on the wire a connector answers: a page with `complete`, `last`, `cursor`, `checkpoint`, `narrowed`, `enumerated` and `capped`, a refused cursor, a failure after so many pages, a fetched body as text or base64, a push result per outcome the case feeds back and none for a push it leaves unreported. A mutation is built by the app's own `Mutation` factories, its handle read off the store by `seq`, its body read back through `PimdirBlobs.get` as a producer's drain reads it, its `syncsTarget` by `PimdirStorage.syncsTarget`.
- **Compares** the pushes in order on the keys the case carries, the events as a multiset, `failed`, `parked`, then each table the case states on the columns its expected rows name, by the queries of io-pimdir's runner: flag sets and lists as arrays, `retained_at`, `covered_at` and `round_started_at` as present or absent, an object by its row and the file under its name. Every difference of a case is reported at once.

## The seam

The app's reports count events and name none, and no export names a member off its header. rust/src/ffi/vector.rs, compiled in a debug build alone (`#[cfg(debug_assertions)]`, the host library the tests load; the release libraries of every APK hold none of it), adds four exports the test class `org.pimalaya.client.SyncVectors` declares:

- `sync`, `upgrade` and `mutate` take what `Native.offlineSync`, `offlineUpgrade` and `offlineMutate` take and run the same coroutine through the same `Driver` over the same `JniHost`: the coroutine is built by `OfflineSync::coroutine`, `upgrading` and `mutating`, which the production exports now build theirs by. They answer `{events, failed, parked?, refusal?, error?}` where the exports answer counts, a sync its driver failed answering what its landed pages did (`PimdirSync::report`), as SYNC §5 has it.
- `named` answers what a scripted listing names a member by: `derive_meta` over a message's fixture with the size and the attachment mark the case states, a file's stated key and summary, `derive` for another kind.

What the seam bypasses: the report mirrors (`SyncReportJson`, `UpgradeReportJson`, `MutateReportJson`) and the three JNI export functions themselves, nothing of the load, the engine, the wire or the write. What the runner bypasses: the app's connectors, and the naming a non-mail driver does before the engine (`PimdirEngine.named`), a case's listing naming its members itself as a mail listing does. The production change is a refactor: `Driver::drive` borrows its coroutine, so a caller reads its report after a failure.

One field is added on the wire by the runner: `overrule` on an edit (SYNC §7), which the bridge reads and no producer of the app states (vectors 100, 101, 102 and 105, which pass through the Java write's `overrule` op).

## Two fixes

Each failed first, in the first run of the suite.

- **The load states the copies it offers.** Vectors 93 and 99: expected events `[Added 77]`, got `[Added 77, Disagreed 77 basic-1@example.org]`, the rows equal. `PimdirStorage.loadCollection` projected an item another source holds as a create and did not say it was an offered copy (`PimdirLoaded::offered`, which the wire already read), so the engine took it for the source's own create. It now lists `offered`. The engine reads it only to decide that report; the app reads no event.
- **A page handing no cursor is not the last for that.** Vector 79: the run did not fail, 10 was dropped (`Vanished`, its binding gone, the item retained) and the round closed with coverage. The wire read `last: wire.last || cursor.is_none()` (rust/src/offline/wire.rs), so a page stating it is not the last and handing no cursor closed the round, where the engine refuses it. It reads `last` as stated. No connector of the app sends such a page: a mail page states `last` as `cursor.is_none()` (rust/src/mail/listing.rs), the others state neither and close.

## The findings left

Eleven cases fail. None is fixed here.

1. **The write keeps no body a collection's policy did not adopt** (SYNC §9, STORAGE §11). Vectors 85, 86, 90, 91, 104, 114, 115, `collections.conflict` at `prefer-incoming` or `prefer-existing`. Expected: an `Overruled` event and a retained item keyed `dup:<link>#<handle>` holding the body the policy did not adopt (85: `dup:urn:uuid:4fbe8971-0bc3-4e1f-8a2d-1c9b7f6e5d40#b.vcf`, object c3, deleted 1, retained by b, seq 2). Got: no event, no such row; the item's own row, its bindings and its conflict are as expected. The merge the write asks of the bridge (`PimdirStorage.merge`, `PimdirSql.merge`, rust/src/offline/merge.rs `MergedJson`) answers the body the item keeps and not the one it lost, and `applyUpsert` keeps one only on an `overrule` op. The app sets no policy: every collection it creates is `manual`, so the body is lost only in a store whose policy something else set.
2. **A retained row revived at meta keeps its retained body** (STORAGE §11.1, SYNC §6). Vector 108. Expected: 00000042oc1nst4nce live at level 1 with no body and the listed size 23, its retained body n1 set aside as `dup:00000042oc1nst4nce#00000042oc1nst4nce` (seq 3, retained), `Overruled` after `Added`. Got: the row live at level 2 holding n1, size 15, no second row, no `Overruled`. `applyUpsert` restates the row it finds (`RESTATE_ITEM`) with what the merge kept. Kept so, the body differs from a base naming none and projects dirty: an update gated on the new revision, over a body nobody read. The app's DAV drivers read a body before a member reaches the store, so their revived cards arrive at full.
3. **A create's origin** (SYNC §3). Vector 49. Expected: an `Add` of `\u0001dup:basic-1@example.org#\u0001basic-1@example.org` with origin INBOX 10, accepted as 4. Got: no push, the create left pending. `PimdirStorage.originOf` reads the app's own `bound_elsewhere` (rust/pimdir/queries/app/bound_elsewhere.sql), which departs from the canonical `origin_for_link` twice: it leaves out a member whose item is a tombstone (`i.deleted = 0`), which INBOX 10 is, and it bounds the account by the collection id's prefix, which the case's ids do not carry, every source of the app being named `server`.
4. **The summary a load carries** (SYNC §6, Annex A.1, A.7). Vectors 52 and 58. 52 expected `mail_summary` of basic-1@example.org with size 299 and attachment 0, got both null. 58 expected `file_summary.size` 23, got 15. A load carries a message's date, subject and sender alone and no summary of another kind (`PimdirStorage.placementOf`), so the engine, keeping what a held body told over what a listing states (`summary::without_body`), keeps nothing, and `PimdirSummary.write` writes what it is handed. A listing naming a member the store holds the body of reaches this: the mail drivers list a bound member by id and markers where they can (`MailEngine.nameUnnamed`), a page carrying summaries for bound members does not.

Noted, failing no case: a `Links` load answers an offered copy too, where SYNC §10 has it answer the source's holders alone; the bridge never tells the engine other sources bind a collection (`beside_other_sources`), which a delete the rights forbid would read, and the app forbids none.

## Unbound

Thirty cases the bridge cannot run: rekey, which it has no verb for (10, 22, 23, 24, 29, 89, 95, 96); a head, which it lists none of (62 to 69); the engine's `prefer-local` and `prefer-remote`, the bridge syncing under `manual` alone (14, 15, 33, 83, 97, 116, 117); a sync without push (08); an upgrade to the meta tier (07); `Abandon`, which its wire does not state (150, 151, 157); and vectors 70 and 158, which bind an owner recording attachments in the engine's write, where the app records them when a body is stored.

## Checked

That the runner sees a difference: seven expectations altered in a scratch copy of the vectors (the events of 01, a source row of 02, a binding's base flags in 03, `failed` in 04, a push of 05, a summary row of 06, an object of 130), each reported as an unlisted failure.

With `PIMALAYA_IO_PIMDIR_SPEC` at the spec/ of io-pimdir cd33f0e as cargo fetched it: `cargo fmt --check`, `cargo clippy --offline --all-targets -- -D warnings`, `cargo test --offline` (658 passed, 1 ignored), `:app:assembleDebug`, `:app:testDebugUnitTest` (1,021 tests, 5 skipped) and `:client:testDebugUnitTest` (14), offline, on the host. Nothing was installed nor run on a device, and no account was reached. The working tree of ../io-pimdir had moved past the pin that day, an uncommitted vector 159 among it, a rekey: pointed there, the suite fails on that one case not being listed, as it is meant to when a pin brings a case.

Capabilities moved: offline-store ("The sync vectors run through the app's load and write", "A load states the copies it offers", "A page closes its listing only when it says so" added).

Left: the four findings, each a change of its own; the unbound verbs; pimdir's ledger line A2, which is its owner's to close.
