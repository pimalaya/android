---
cairn: change
id: release-supply-chain
status: active
created: 2026-10-18
---

# Signing, Gradle verification and advisories

## Why

The S4/S5 audit of 2026-10-11 left three findings open in this repository (H1, L1, L3 and L4 are fixed). Checked on 2026-10-18:

- **M1, the release key in the whole job.** .github/workflows/releases.yml runs on every push to master as well as on `v*` tags (lines 3 to 8). `PIMALAYA_KEYSTORE_PASSWORD` sits at job level (line 19), the keystore is decoded to `$RUNNER_TEMP` and exported through `GITHUB_ENV` (lines 39 to 46), so `gradle :app:assembleRelease`, which also runs cargo-ndk, hands the password and the key to every crates.io build script and every Gradle plugin, and every master push produces a signed APK. Every action is pinned by a mutable tag: actions/checkout@v4, cachix/install-nix-action@v31, cachix/cachix-action@v17, Swatinem/rust-cache@v2, gradle/actions/setup-gradle@v6, actions/upload-artifact@v4, softprops/action-gh-release@v2 (the same in tests.yml and audit.yml). No workflow declares `permissions:`, and the Cachix push token (`CACHIX_AUTH_TOKEN`) reaches every job.
- **M7, Gradle trusts what it resolves.** No android/gradle/verification-metadata.xml and no wrapper. `com.github.requery:sqlite-android:3.49.0` (app/build.gradle.kts:190) resolves from JitPack (settings.gradle.kts:16, scoped to `com.github.requery`), which builds it from a mutable tag, and its aar ships the native `libsqlite3x.so` that becomes the app's database engine. The audit recorded its sha256 to pin: 2a746d7d9d68f4393d80bbca4e2af07643bc963e22ef737a7fa035d70e07aa70. Plugins also come from gradlePluginPortal().
- **M4, advisories checked on push only.** deny.toml holds `[sources]` and `[licenses]` only: no `[advisories]`, no `[bans]`, no `[graph] all-features = true` (an optional feature's advisory goes unseen). .github/workflows/audit.yml triggers on `push` alone, so an advisory published between pushes is not reported.

## What

- Releases: build unsigned in one job; sign in a separate job, on tags only, behind a protected GitHub environment, with `apksigner` and the secret scoped to that one step; every action pinned by commit SHA; `permissions: contents: read` at the top of each workflow, `contents: write` on the publishing job alone; the Cachix push token only where a release build pushes, a read-only one elsewhere.
- Gradle: `gradle --write-verification-metadata sha256 :app:assembleRelease` committed (`verify-signatures` where publishers sign); sqlite-android pinned to a JitPack commit, or SQLite built from source in the flake (F-Droid's preference); a wrapper or an F-Droid srclib, which F-Droid needs anyway.
- cargo-deny: `[advisories]` (yanked denied), `[bans]` (multiple versions warned), `[graph] all-features = true`; audit.yml also on `schedule: - cron: "0 6 * * 1"`.

## Out of scope

- The keystore copies in worktrees (S5 M2) are the user's to clean, outside the repository.
- The libraries' own CI (io-imap's trace-level live jobs, its stale deny entry): their repositories.
