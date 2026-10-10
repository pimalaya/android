---
cairn: tasks
change: bundled-sqlite
---

# Tasks

Java paths are relative to android/app/src/main/java/org/pimalaya/, tests to android/app/src/test/java/org/pimalaya/.

- [x] android/settings.gradle.kts: JitPack repository, restricted to `com.github.requery`
- [x] android/app/build.gradle.kts: `com.github.requery:sqlite-android:3.49.0`; the test JVM's library path takes the host `libsqlite3x.so`
- [x] flake.nix: a host build of the binding's JNI and SQLite 3.49.0 amalgamation with the AAR's flags, exported to the devshell
- [x] Swap `android.database.sqlite.{SQLiteDatabase, SQLiteOpenHelper, SQLiteStatement, SQLiteCursor, SQLiteQuery}` for `io.requery.android.database.sqlite` in every app class and test; keep `Cursor`, `MatrixCursor` and the platform exceptions
- [x] Docs and comments naming "Android's own SQLite" or the API 34 floor follow
- [x] Test: `sqlite_version()` >= 3.37, `STRICT`, `RETURNING`, `UPDATE ... FROM`, `json_each` through `PimdirDb`
- [x] APK size before and after
- [x] CHANGELOG
- [x] Build: `:app:assembleDebug :app:testDebugUnitTest` green
- [ ] Device test: a store created and synced on an Android 8 to 13 device and on Android 14+
- [x] Fold the delta into cairn/spec/offline-store.md and write the log entry
- [ ] Archive as landed after the device test
