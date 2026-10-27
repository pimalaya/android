---
cairn: tasks
change: release-supply-chain
---

# Tasks

Paths are relative to the repository root.

## Signing (S4 M1)

- [ ] .github/workflows/releases.yml: the build job unsigned, no keystore secret (lines 19, 39-46 go)
- [ ] A sign job on `v*` tags only, in a protected environment, `apksigner` over the build's artifacts with the password and keystore scoped to that step; the GitHub release made from its output
- [ ] Actions pinned by SHA in releases.yml, tests.yml and audit.yml (a comment naming each tag)
- [ ] `permissions: contents: read` per workflow, `contents: write` on the publishing job only
- [ ] Cachix: push token on the release build alone, a read-only token for tests and audit
- [ ] app/build.gradle.kts signing block (lines 5-10, 42-64): kept for local signed builds, or dropped if CI signs with apksigner alone

## Gradle (S4 M7)

- [ ] android/gradle/verification-metadata.xml written with sha256 (`--write-verification-metadata sha256 :app:assembleRelease`) and committed; `verify-signatures` where publishers sign
- [ ] sqlite-android pinned to a JitPack commit (or SQLite built from source in the flake); its aar checked against sha256 2a746d7d9d68f4393d80bbca4e2af07643bc963e22ef737a7fa035d70e07aa70
- [ ] A Gradle wrapper or an F-Droid srclib

## Advisories (S4 M4)

- [ ] deny.toml: `[advisories]` with `yanked = "deny"`, `[bans]` with `multiple-versions = "warn"`, `[graph] all-features = true`
- [ ] .github/workflows/audit.yml: `schedule: - cron: "0 6 * * 1"` beside `push`
- [ ] Once release-pinned-libs drops the git patches, tighten `[sources]` (no git source allowed)

## Close

- [ ] `cargo deny check` green; a release dry run on a test tag in a fork
- [ ] Log written; CHANGELOG if the published artifacts change
