---
cairn: tasks
change: release-supply-chain
---

# Tasks

Paths are relative to the repository root.

## Signing (S4 M1)

- [x] .github/workflows/releases.yml: the build job unsigned, no keystore secret (lines 19, 39-46 go). Done 2026-11-19: the `apk` job builds unsigned, the Gradle signing config is gone, so no keystore secret is in the job
- [x] A sign job on `v*` tags only, in a protected environment, `apksigner` over the build's artifacts with the password and keystore scoped to that step; the GitHub release made from its output. Done 2026-11-19: job `sign`, `environment: release`, after `apk` on `refs/tags/v*`; the keystore decoded, used (`zipalign -c -P 16`, `apksigner sign` and `verify` per APK, `jarsigner` for the bundle Play takes) and deleted in one step; softprops publishes signed/*.apk, the signed bundle stays an artifact (signed-pimalaya-aab). Owed: the `release` environment and its secrets (owner)
- [x] Actions pinned by SHA in releases.yml, tests.yml and audit.yml (a comment naming each tag). Done 2026-11-19, majors unchanged, SHAs resolved with `gh api` (checkout v4.4.0, install-nix-action v31.11.5, cachix-action v17 (no point tag), rust-cache v2.9.3, setup-gradle v6.4.1, upload-artifact v4.6.2, download-artifact v4.3.0, action-gh-release v2.6.2)
- [x] `permissions: contents: read` per workflow, `contents: write` on the publishing job only. Done 2026-11-19: `sign` is the one `contents: write` job
- [x] Cachix: push token on the release build alone, a read-only token for tests and audit. Done 2026-11-19: `CACHIX_AUTH_TOKEN` in releases.yml `apk` only; tests, audit and `sign` read with `CACHIX_AUTH_TOKEN_READ` and `skipPush: true`. Owed: the read-only token created in Cachix and stored as that secret (owner)
- [x] app/build.gradle.kts signing block (lines 5-10, 42-64): kept for local signed builds, or dropped if CI signs with apksigner alone. Dropped 2026-11-19: one signing path (apksigner, jarsigner) for CI and local builds; a Gradle signing config would hand the password to cargo-ndk and every plugin again. CONTRIBUTING.md says how to sign a local build

## Gradle (S4 M7)

- [x] android/gradle/verification-metadata.xml written with sha256 (`--write-verification-metadata sha256 :app:assembleRelease`) and committed; `verify-signatures` where publishers sign. Written 2026-11-19 with Gradle 8.14.4 from the devshell over `:app:assembleRelease :app:bundleRelease :app:assembleDebug :app:testDebugUnitTest` (334 components, sha256 only); `assembleRelease`, `assembleDebug` and a unit test pass with it, and a hash altered by hand fails `:app:debugRuntimeClasspath` as it should. `verify-signatures` left off: Google Maven, which serves AGP and AndroidX, publishes no PGP signatures, so sha256 is the floor; a dependency bump now needs `--write-verification-metadata sha256` again. Robolectric fetches its android-all jars itself, outside Gradle verification
- [-] sqlite-android pinned to a JitPack commit (or SQLite built from source in the flake); its aar checked against sha256 2a746d7d9d68f4393d80bbca4e2af07643bc963e22ef737a7fa035d70e07aa70. Not applicable (2026-11-19): platform-sqlite moved the store onto the platform android.database.sqlite and dropped requery from Gradle (android/app/build.gradle.kts has no sqlite-android dependency, no JitPack repository is left); requery source is fetched by hash in flake.nix only for the host test JNI, nothing of it ships
- [ ] A Gradle wrapper or an F-Droid srclib. Investigated 2026-11-19, nothing added: an F-Droid srclib is for source libraries, not Gradle. F-Droid builds with gradlew-fdroid in place of gradlew, which takes the Gradle version from gradle/wrapper/gradle-wrapper.properties (`distributionUrl`, verified against its checksums), else guesses from the AGP version, a guess whose pattern misses the Kotlin DSL `id("com.android.application") version` form android/build.gradle.kts uses. Recommended at the F-Droid recipe: android/gradle/wrapper/gradle-wrapper.properties alone (no jar, no gradlew scripts), naming the devshell Gradle (8.14.4) with `distributionSha256Sum`, bumped with nixpkgs; until then the flake pins Gradle for CI and contributors

## Advisories (S4 M4)

- [x] deny.toml: `[advisories]` with `yanked = "deny"`, `[bans]` with `multiple-versions = "warn"`, `[graph] all-features = true`. Done 2026-11-19
- [x] .github/workflows/audit.yml: `schedule: - cron: "0 6 * * 1"` beside `push`. Done 2026-11-19
- [x] Once release-pinned-libs drops the git patches, tighten `[sources]` (no git source allowed). Done 2026-11-19: rust/Cargo.lock holds crates.io sources only, so `allow-git = []`, crates.io the one registry, the pimalaya org allowance gone

## Close

- [ ] `cargo deny check` green; a release dry run on a test tag in a fork. 2026-11-19: sources, bans and licenses ok; advisories green since RUSTSEC-2026-0543 (chrono unmaintained, imposed by imap-types) is ignored with a reason, the switch in progress upstream (imap-codec issue 752, owner 2026-11-19). Dry run in a fork owed (owner)
- [ ] Log written; CHANGELOG if the published artifacts change
