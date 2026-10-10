---
cairn: change
id: bundled-sqlite
status: active
created: 2026-10-10
---

# The store runs on a bundled SQLite

## Why

io-pimdir's store needs SQLite 3.37 or newer: every table is `STRICT` (3.37), statements use `RETURNING` (3.35), `UPDATE ... FROM` (3.33) and `json_each` (JSON1). The app executes them on the platform's SQLite (`android.database.sqlite`), which is 3.18 to 3.32 on Android 8 to 13 (API 26 to 33). Below Android 14 the store cannot even be created, so the app is unusable on most of the devices its `minSdk = 26` admits.

## What

Bundle SQLite into the APK for every device: one variant, `minSdk` stays 26, no per-SDK split.

- **Dependency**: `com.github.requery:sqlite-android:3.49.0` (JitPack), SQLite 3.49.0 built with JSON1, FTS5 and R-Tree. It is the AOSP `android.database.sqlite` code under `io.requery.android.database.sqlite`, so the swap is a package change: same `SQLiteOpenHelper`, connection pool, WAL, transactions and `Cursor` semantics, and it throws the platform's exception classes. Apache 2.0 over public-domain SQLite.
- Considered and set aside: sqlite.org's own bindings (`org.sqlite.database.sqlite`, the same AOSP lineage, SQLite 3.54) are published as a downloadable AAR only, not on any Maven repository, so taking them means committing a binary or building the amalgamation in-tree; `androidx.sqlite:sqlite-bundled` (Google Maven) is a low-level statement API with no `SQLiteOpenHelper`, `Cursor` or connection pool, so the store would be rewritten around it and its thread safety reimplemented.
- **Every** `android.database.sqlite` database the app opens moves, the pimdir store and the contacts database alike, so one SQLite engine runs in the process. `android.database.Cursor`, `MatrixCursor` and the platform exception classes stay: the bundled classes implement and throw them, and the content-provider reads (phone contacts and calendars) are the platform's by nature.
- **Host tests**: the unit tests run on Robolectric, where the AAR's Android `.so` cannot load. The flake builds the same binding's JNI and the same SQLite amalgamation for the host (`libsqlite3x.so`, pinned sources and flags), and the test JVM loads it beside `libpimalaya.so`, so the store tests run the bundled Java binding and SQLite version rather than Robolectric's.
- A test asserts, through the store's own opener, that `sqlite_version()` is at least 3.37 and that a `STRICT` table, `RETURNING`, `UPDATE ... FROM` and `json_each` work.
- With 3.35+ everywhere, `ALTER TABLE ... DROP COLUMN` always works: the reconcile's "kept with a log" fallback stays only as a guard.
- CHANGELOG: the app works on Android 8 to 13.

Trade-offs: about 1 MB of native code per ABI; JitPack builds the AAR from the tagged source but F-Droid's build would take the prebuilt `.so` from it rather than compile SQLite (see the log entry).
