---
cairn: log
change: a-stamp-is-no-phone-change
date: 2026-11-21
---

# A stamp is no phone change

Follows cairn/log/2026-11-21-the-store-answers-folded-adopted-refused.md, whose two leftovers land here: the phone spokes' revision moving after a stamp, and the vector tests needing a hand-set `PIMALAYA_IO_PIMDIR_SPEC`. A fix without a proposal.

## The stamp is the listed revision

A phone row an app edited lists at `dirty:` (the calendar rows' fingerprint, a raw contact's `VERSION`), and the engine records that revision with the body it fetches (SYNC §5). The read then stamped the row clean at the body's hash, so the next listing named a revision the store never recorded: a change nobody made, pulled again, recorded as a binding conflict over an item's conflict, and an `If-Match` a push over the stamp would miss.

- android/app/src/main/java/org/pimalaya/calendar/CalendarRemote.java `read` stamps the rows, or writes the store's body back over a refused edit, at `revision(target, handle)`, the revision the listing names them at; `write` and `split` take the revision to stamp, the body's hash where nothing else is named.
- android/app/src/main/java/org/pimalaya/contact/PhoneRemote.java `read` stamps a dirty or never-stamped raw contact at `revisionOf(SYNC1, DIRTY, VERSION)` and answers it; a clean stamped one is left as it is. A race lost leaves the contact dirty, listing a new `VERSION`.
- `SYNC_DATA1` and `SYNC1` stay opaque revisions: nothing reads them as a body's hash.

Regression tests, both failing on the code of 77472a5 (its CalendarRemote.java and PhoneRemote.java put back for the run, then the fix restored):

- CalendarMirrorTest aReadPhoneEditListsAtTheRevisionTheStoreRecorded: `expected:<[dirty:hwt7z4l7upixymjcs62heyvmjq]> but was:<[fh37pcvtmjdxlqg3jgntcgoxpeucqtzk2xq536ezjjrhkd6drxfa]>`.
- PhoneRemoteTest aReadEditIsStampedAtTheRevisionItWasListedAt, over a raw contacts provider of its own: `expected:<[dirty:3]> but was:<[fbx6wacclsg6epujnee3ibics7ts3hsdmtppydig3akfgvmflxta]>`.

## The spec vectors from the flake

- flake.nix pins `ioPimdirVersion` 0.19.0 and fetches the io-pimdir tag `v0.19.0` from GitHub (`fetchFromGitHub`, 14 MB), and the devshell exports its spec/ as `PIMALAYA_IO_PIMDIR_SPEC` unless one is set before entering. It reads the version rust/Cargo.lock locks and, while the two differ, exports nothing and says so, rather than handing the tests another version's vectors. Bumping the pin means its `rev` and its `sha256` together: a fixed-output fetch under the old hash keeps the old tree.
- .github/workflows/tests.yml drops its own fetch of the spec: the devshell's serves the Rust and Android unit tests in CI as it does locally.
- The errors of rust/src/offline.rs and android/app/src/test/java/org/pimalaya/pimdir/PimdirVectors.java name the flake's pin.

## Evidence

2026-11-21, `PIMALAYA_IO_PIMDIR_SPEC` unset before entering the devshell: `cargo fmt --check` clean, `cargo test --locked` 738 passed, 1 ignored, `cargo clippy --all-targets --locked -- -D warnings` clean; `gradle -p android :app:testDebugUnitTest :app:assembleDebug --console=plain` BUILD SUCCESSFUL, 1066 tests, 0 failed, 5 skipped, the five debug APKs built.

## Capabilities moved

- carddav-sync (cairn/spec/carddav-sync.md) and calendar (cairn/spec/calendar.md): a phone edit read is stamped at its listed revision.
