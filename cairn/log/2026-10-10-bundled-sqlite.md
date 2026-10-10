---
cairn: log
change: bundled-sqlite
landed: 2026-10-10
---

# The store runs on a bundled SQLite

Capabilities moved: offline-store (added: the store runs on the bundled SQLite; modified: a retired column is kept only where SQLite refuses the drop).

**Why.** io-pimdir's schema needs SQLite 3.37 (`STRICT`, plus `RETURNING`, `UPDATE ... FROM`, `json_each`); Android ships 3.18 to 3.32 on API 26 to 33, so the store could not be created below Android 14.

**Dependency.** `com.github.requery:sqlite-android:3.49.0` from JitPack (repository restricted to `com.github.requery` in android/settings.gradle.kts): the AOSP `android.database.sqlite` code under `io.requery.android.database.sqlite`, SQLite 3.49.0 built with JSON1, FTS5 and R-Tree, Apache 2.0 over public-domain SQLite. Its own `androidx.core:core:1.15.0` is excluded: it would pull Kotlin coroutines, lifecycle and an app-startup provider for `androidx.core.os.CancellationSignal`, which the core 1.3 the drawer resolves already carries. `androidx.sqlite:sqlite:2.4.0` stays (the binding implements its interfaces), bringing the Kotlin stdlib, which R8 strips in release. Set aside: sqlite.org's bindings (same lineage, 3.54) exist only as a downloadable AAR, on no Maven repository; `androidx.sqlite:sqlite-bundled` has no `SQLiteOpenHelper`, `Cursor` or connection pool, so the store would have been rewritten around it.

**Swap.** Every app database moved, the pimdir store and `CardStore`'s contacts database, so one engine runs in the process: `SQLiteDatabase`, `SQLiteOpenHelper`, `SQLiteStatement`, `SQLiteCursor` and `SQLiteQuery` imports in 12 main classes and 11 tests. `Cursor`, `MatrixCursor` and the `android.database.sqlite.*Exception` classes stay, the binding implementing and throwing them. `FakeCalendarProvider` (test) stays on the platform SQLite: it stands in for the system calendar provider, which runs on the device's own.

**Host tests.** The AAR's `.so` is Android-only, so flake.nix builds the same binding JNI over the same amalgamation (pinned hashes, the AAR's Android.mk flags, a stderr stub for liblog and a `C_JNIEnv` shim for OpenJDK's jni.h) as a host `libsqlite3x.so`, exported as `PIMALAYA_SQLITE_HOST`; the test JVM's library path takes it beside `libpimalaya.so` and the test task refuses to start without it. Robolectric's own SQLite is no longer what the store tests exercise. `PimdirDbTest.theStoreRunsOnASqliteNewEnoughForTheSchema` checks, through `PimdirDb`, `sqlite_version()` >= 3.37, a `STRICT` refusal, `RETURNING`, and `UPDATE ... FROM json_each`. Limit: on the host this is the binding over the host build of the same sources, not the AAR's Android library itself.

**What the implementation corrected.** The bundled pool keeps WAL reader connections that Robolectric's SQLite did not, and a bare `PRAGMA` on a reader answers from that connection's cached schema, missing what the primary connection just created. `PimdirDb.columnsOf`, read during the reconcile outside a transaction, now selects from `pragma_table_info(?)`, which notices a schema change; the test helper reading an index does the same with `pragma_index_info`.

**Size.** Release APKs (R8), without and with: arm64-v8a +1.89 MB (27.38 to 29.27 MB), armeabi-v7a +1.29 MB, x86_64 +1.90 MB, x86 +1.92 MB, universal +6.88 MB; nearly all of it the stored `libsqlite3x.so` (1.2 to 1.9 MB per ABI). Debug arm64-v8a +2.69 MB, the Kotlin stdlib unshrunk.

**F-Droid.** JitPack builds the AAR from the tagged source and is a repository F-Droid's scanner accepts, but F-Droid's build takes the prebuilt `.so` from the AAR rather than compiling SQLite; a recipe wanting everything built from source would build the binding as a srclib, as the flake's host build does. Pinning the AAR's checksum (Gradle dependency verification) would guard against a rebuilt artifact, and is not done.

**Left open.** sqlite.org's bindings ship 3.54 while requery's last release is 3.49.0 (2025-05); a newer engine means a requery release or moving to sqlite.org's bindings built in-tree.
