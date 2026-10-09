---
cairn: log
change: release-hygiene
landed: 2026-10-09
---

# Release builds: no debug logs, no adb hook, signed, tested in CI

Capabilities moved: none in the spec's words; release builds stop doing what only a debug build needs.

- R8 strips `Log.d` and `Log.v` from release builds (`-assumenosideeffects` in android/app/proguard-rules.pro): they carried OAuth authorize URLs, client ids and scopes, addresses and raw engine replies.
- The `syncRemote` intent extra on the exported launcher, which starts a sync from adb, is honoured by debug builds only (`BuildConfig.DEBUG`, `buildConfig` enabled for it).
- io-pimdir comes from crates.io (0.7.0) rather than a local path patch, so CI resolves it.
- The release workflow hands gradle the `PIMALAYA_KEYSTORE*` variables it reads, mapped from the repository's `CARDAMUM_KEYSTORE*` secrets with that keystore's `cardamum` alias (`PIMALAYA_KEYSTORE_ALIAS`, `pimalaya` when unset), and publishes `pimalaya*.apk`. Since the rename it would have shipped unsigned APKs; every run since 2026-08-08 failed earlier, on the path patches or on lint.
- Three French strings with no default (`compose_from`, `compose_sent`, `compose_sent_no_copy`, left by earlier rewording) are dropped: `lintVitalRelease` refused them, failing every release build.
- A Tests workflow runs clippy, the Rust tests and `:app:testDebugUnitTest` on every push and pull request to master.
- PRIVACY.md states what the app does today: whole messages stored, full calendar objects, deleted-items retention, the phone mirrors, background sync and notifications, the autoconfiguration lookups, and Google's Limited Use statement.

Found by the read-only production review of 2026-10-09 (docs/production-review.md).
