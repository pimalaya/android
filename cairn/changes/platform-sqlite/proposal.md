---
cairn: change
id: platform-sqlite
status: active
created: 2026-10-31
---

# The store and the index on the platform's SQLite, from API 26

## Why

The user, 2026-10-31: the app should run on the platform's `android.database.sqlite` with no SQLite of its own, from API 26 (minSdk 34 drops too many devices), its store and index in a shape of its own that tends toward pimdir's recommendations as the floor rises. pimdir allows it once pimdir cairn/changes/the-sqlite-floor-is-a-recommendation lands.

What it settles:

- **No second SQLite.** requery (cairn/log/2026-10-10-bundled-sqlite.md) goes, about 1.9 MB per ABI. The index needs no rusqlite in the cdylib, because io-pimdir's refresh becomes I/O-free (io-pimdir cairn/changes/an-io-free-indexer) and Java writes index.db on the same SQLite as the store: one copy in the process, the two-SQLites question of cairn/changes/lists-from-the-index gone.
- **The draft-05 port** (cairn/changes/io-pimdir-draft-05) lands as part of the shape's rewrite rather than as a port of the canonical text.
- **The SQL Java writes by hand** (`RETURNING` 11, `json_each` 9, upserts 7, `STRICT` 5 across PimdirDb, PimdirStorage, PimdirItems, PimdirCollections, DeletedItems, MailStore, and PimdirContacts' transcribed reads) moves into the shape's .sql files, as AGENTS.md asks.

What the platform gives, checked 2026-10-31: SQLite 3.18 on API 26 up to 3.50 on 37, the version possibly different on a manufacturer's build ([android.database.sqlite](https://developer.android.com/reference/android/database/sqlite/package-summary)); FTS3 and FTS4 (one module), never FTS5, no JSON extension, the basic FTS query syntax (no `SQLITE_ENABLE_FTS3_PARENTHESIS`), `SQLITE_THREADSAFE=2`, `SQLITE_SECURE_DELETE`, autovacuum on (AOSP external/sqlite, dist/Android.mk at oreo-release, dist/Android.bp on later releases; `SQLITE_DEFAULT_LEGACY_ALTER_TABLE` only from 3.26, 3.18 altering the legacy way anyway); JSON in the core only from 3.38 (API 34); `execSQL` of an `ATTACH` disables WAL on that database (SQLiteDatabase.java); SQL functions registered from Java only from API 30, string arguments only (`setCustomScalarFunction`); no NDK access to the platform library (no stub in the NDK sysroot, private libraries refused since API 24); rusqlite cannot link requery's libsqlite3x.so either (252 exports, `sqlite3_bind_parameter_count`, `sqlite3_clear_bindings`, `sqlite3_bind_parameter_index`, `sqlite3_create_function_v2` among the missing).

## Across the repositories, in order

1. **pimdir**, cairn/changes/the-sqlite-floor-is-a-recommendation: the floors RECOMMENDED, `store_meta.shape`, no JSON in the canonical schema (flag tables, `temp.param`, `queue.target_collection`, `mail_in_reply_to`, `task_category`), `CASE` for `iif`, `sqlite_master`, the fold before the tokenizer, model vectors apart from the canonical shape's. storage-draft-07, search-draft-07, sync-draft-06.
2. **io-pimdir**, cairn/changes/an-io-free-indexer: `search` without `client`, the refresh as the `PimdirIndexRefresh` coroutine yielding typed ops, `pimdir_fold` gone, the store on draft-07. Released or pushed as 0.9.0 by the user.
3. **This app**, the five steps below. Step 1 depends on nothing and can start first; step 2 waits on 1 and 2 above; step 4 on the coroutine.

## What

**The app's shape, `pimalaya-android`** (`store_meta.shape`), the model of storage-draft-07 on SQLite 3.18 without JSON, FTS4 for the index. Its text lives in this repository as .sql files (rust/pimdir/migrations/, rust/pimdir/queries/), embedded by the bridge and served by `Native.pimdirSql` and `Native.pimdirMigrations` as the canonical text is today, statement names kept wherever the effect is the canonical one. Against the canonical draft-07 text:

| Canonical | 3.18 | Statements |
|---|---|---|
| `STRICT` (3.37) | `CHECK (typeof(col) IN (...))` per column | every table |
| `RETURNING` (3.35) | the write, then a `SELECT` by `changes()`, `last_insert_rowid()` or the key, in one transaction | 14 canonical, 11 in Java today |
| `ON CONFLICT ... DO UPDATE / NOTHING` (3.24) | `INSERT OR IGNORE`, then an `UPDATE` by key, as one named pair | 27 canonical, 7 in Java today |
| `UPDATE ... FROM` (3.33) | correlated subqueries | recompute_refcounts, release_bases_before |
| `DROP COLUMN`, `RENAME COLUMN` (3.35, 3.25) | the §6 table rebuild | reconciliation only |
| FTS5 `contentless_delete` (3.43) | FTS4, `prefix="2,3"`, `tokenize=unicode61 "remove_diacritics=0"` over text io-pimdir folded (pimdir SEARCH §5: `simple` splits on ASCII alone and would answer other hit sets); contentless with a live-row table, or ordinary (Open) | the index |
| FTS5 `bm25`, `Relevance` | not offered: `Date` (the default) and `Name` only | the search order |

**Two databases, one SQLite.** pimdir.db stays the store, WAL, its owner `PimdirDb`. index.db is the index, written by a Java `PimdirIndexDb` servicing `PimdirIndexRefresh`'s yields, the only writer, under the store's process lock. Queries open index.db with pimdir.db attached, on a handle of its own: the attach costs that handle its WAL, so index.db runs in rollback-journal mode, a refresh committing in chunks so a search waits at most one chunk. The index tables inside pimdir.db were set aside: the user wants the store and the index apart, and a refresh would contend with sync writes for the store's one writer.

**The bridge.** io-pimdir stays without `client`, now with `search` (pure). The bridge drives the refresh as it drives the sync engine, and compiles a `PimdirQuery` to the shape's SQL (rust/src/search/, its own compile beside io-pimdir's canonical src/client/search/compile.rs), one `MATCH` per text term with the boolean logic in SQL, as the canonical compile does, so FTS4's basic syntax suffices. Java runs the statement and hands the rows back; snippets come from the body in Rust (as src/client/search.rs `snippet` does), never from FTS4's `snippet()`.

**The floor is tested, not trusted.** flake.nix builds SQLite 3.18.2 with Android 8.0's flags (dist/Android.mk) (no JSON, FTS3 and FTS4, no FTS5, the basic query syntax) as a host library. A unit test prepares every statement the bridge serves and runs every migration on it, failing on any newer syntax; it fails first against today's canonical text. The JVM store tests run on the same build in place of requery's host `libsqlite3x.so` (`PIMALAYA_SQLITE_HOST`). An API 26 emulator runs before each device check, and the store logs `sqlite_version()` at open.

## How, in order, each step on the device before the next

1. **Guard.** The host 3.18.2 build and the prepare test, red against today's text.
2. **The shape, on requery still.** The shape's text at draft-07, with the draft-05 port's rules folded in (cairn/changes/io-pimdir-draft-05: `shared_object`, `Overrule`, `WantsVerify`, `Written`, `if_match`, flag deltas, `esc:`) and the hand-written SQL moved in. The pin moves to the io-pimdir release following draft-07. No conversion from today's stores: a store whose `store_meta.shape` is not `pimalaya-android`, or which fails to open, blocks start-up on a screen saying the store's version is not compatible and the app must be reinstalled, and nothing in it is read or written. Guard green. Kept apart from step 3 so a failure on the device points at the SQL or at the engine, not both.
3. **The platform.** `io.requery.android.database.sqlite` imports back to `android.database.sqlite` (15 classes, `CardStore` included), requery out of build.gradle.kts and flake.nix's requery host build replaced by step 1's. A store step 2 wrote opens unchanged, since its text holds nothing 3.18 refuses; an older one, whose `STRICT` schema 3.18 to 3.32 cannot even parse, meets step 2's block.
4. **The index.** index.db's shape, `PimdirIndexDb`, the refresh after each sync pass, after each local write batch (SEARCH §4) and at start, search compiled in the bridge, ordered by `Date` or `Name`.
5. **The lists** (cairn/changes/lists-from-the-index): the agenda through the occurrence table, Tasks and Contacts from the summaries. Measured again with the `agenda read` and `agenda drawn` logs, then the timing log dropped.

## Decided with the user (2026-10-31)

- The platform's SQLite from API 26, the app's own shape a perpetual fork tending toward the recommendations; not minSdk 34.
- Tables instead of JSON, in pimdir's canonical schema itself; `flags_known` and `base_flags_known` columns rather than a sentinel row.
- Store and index in two databases; index.db without WAL on Android.
- No conversion of test-build stores: start-up blocked, reinstall.
- No relevance order for now: `Date` and `Name`, bm25 from `matchinfo` in Rust set aside (FTS4 has no built-in rank, SQL cannot decode `matchinfo` below API 30, so a page would mean ranking every hit over JNI).

## Open

- FTS4 cannot delete from a contentless table. Contentless with a live-row table: a removed object's row stays in the FTS index, filtered out by a join on the live rows, and the index is rebuilt (it is derived) once dead rows pass a share of the whole; small, at the cost of the filter and the rebuilds. Ordinary: FTS4 stores a copy of every text it indexes and deletes normally; simple, at the cost of index.db growing by every extracted text it holds. Measured at step 4 after a large first sync, then decided.
