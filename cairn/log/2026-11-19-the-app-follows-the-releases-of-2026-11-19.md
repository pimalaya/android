---
cairn: log
change: the-app-follows-the-releases-of-2026-11-19
date: 2026-11-19
---

# The app follows the releases of 2026-11-19

The bridge moves onto the family releases published on crates.io on 2026-11-19. A dependency move, no proposal; the previous bumps (cairn/log/2026-11-18-the-app-follows-io-pimdir-0-16.md, cairn/log/2026-11-18-the-store-follows-pimdir-draft-14.md) are the model.

## The manifest

rust/Cargo.toml asks io-pimdir 0.18.0 (the dependency and the dev-dependency), io-imap 0.12.0, io-smtp 0.10.0 and ical-rs 0.7.2. `cargo update -p io-pimdir -p io-imap -p io-smtp -p ical-rs` moved those four and io-sasl 0.2.0 to 0.3.0 in rust/Cargo.lock, nothing else. No `[patch]`, git or path source. `cargo tree -i io-sasl` shows one io-sasl, 0.3.0, under io-imap and io-smtp alone: io-oauth and io-pim-discovery take none.

## Breaking changes met

- **io-imap 0.12 and io-smtp 0.10**: the I/O-free client coroutines moved behind a new `client` feature (default on), and the old `client` feature, the std runtime, is now `std`. The bridge takes both with `default-features = false`, so both lines now ask `features = ["client"]`; nothing of `std`, TLS or `server` is pulled. io-sasl 0.3 gates its client mechanisms the same way, enabled through those `client` features.
- **io-imap's `ImapRaw`** now answers the exact octets of each response (`Vec<Vec<u8>>`): the bridge runs no raw command, nothing moved.
- **io-pimdir 0.18**: no API the bridge calls changed. The format stays storage-draft-14, sync-draft-14 and search-draft-14, so a store this app wrote opens as it was. The sync vectors moved to format 8 (an object's `row`) and gained vector 172.
- **ical-rs 0.7.2**: fixes alone. The JSCalendar conversion of rust/src/calendar/jmap.rs keeps a `LAST-MODIFIED` beside a `DTSTAMP`, and carries a series' `UNTIL` right between JSCalendar and iCalendar: zoned (shifted on the bundled time zone database, `tzdb` being on through `parser`), floating and all-day. CHANGELOG.md says so under Fixed.

## What io-pimdir 0.18 brings

- `PimdirHubItem::reconcile` and `PimdirHubVerdict`: read by rust/src/offline/merge.rs, cairn/log/2026-11-19-merge-reads-the-hub-verdict.md.
- `client::blobs::PimdirBlobMove` (`renameat2` with `RENAME_NOREPLACE`, then `link(2)`, then an exclusive create and copy): not taken. rust/src/offline/blobs.rs keeps its own move. `PimdirBlobMove` is in io-pimdir's `client` feature, which pulls rusqlite, fs4 and rustix into the shared library the app builds without them (the store is the platform's SQLite, through Java); with `renameat2` off it tries `link(2)`, which the app's SELinux policy denies, before the exclusive create; and it answers `Held` for any name held, where the bridge verifies the file holding it (`Filed::Failing`). Swapping would move dependencies and behaviour, not only code.
- `PimdirStore::purge_items`, `PimdirReader::accounts_of` and `overlay_quiet`, `PimdirQuery::scoped`, `PimdirSearchCoverage::unread_counted`, `collection::media_type`: unused. The reader and store ones are in `client`. rust/src/search has its own `scoped` (coverage.rs) and `media_type` (compile.rs, over `PimdirQueryKind` rather than a collection kind); whether io-pimdir's could replace them was not looked at here.

## The vector runner

android/app/src/test/java/org/pimalaya/pimdir/SyncVectorsTest.java reads format 8: `FORMAT` is 8, and an object stating `row` false is seeded as its file alone, no `STORE_OBJECT` row. Vector 172 (a fetch meeting a partial file with no row files it again) passes through the app's load and write with no production change; the ledger android/app/src/test/resources/org/pimalaya/pimdir/sync-vectors.txt did not move.

CI (.github/workflows/tests.yml) fetches io-pimdir's spec at the tag the lockfile names (`refs/tags/v$version`, from `rust/Cargo.lock`), so it follows the pin with no edit.

## Evidence

Each as `nix develop --command <cmd>` from the repository root, `PIMALAYA_IO_PIMDIR_SPEC` at io-pimdir v0.18.0's spec/ (REVISION e188e31, `git archive v0.18.0 spec` from the local clone into the scratchpad), on 2026-11-19, with the merge change in the same tree:

- `cargo fmt --check`, `cargo clippy --all-targets --locked -- -D warnings` in rust/: clean.
- `cargo test --locked` in rust/: 738 passed, 1 ignored.
- `gradle -p android :app:assembleDebug :app:testDebugUnitTest :client:testDebugUnitTest --console=plain`: 1,058 and 14 tests, 5 skipped, none failing; the debug APK built. SyncVectorsTest: 172 cases, 141 pass, none fails, 31 unbound (140, 0 and 31 of 171 at 0.17.0). No JNI signature changed, so `assembleRelease` was not run.
- No Gradle dependency changed, so android/gradle/verification-metadata.xml did not move.
- `cairn/verify.sh`: conformant.

Nothing was installed on a device, no live suite was run and nothing connected to an account.

## Left

- A device check of a JMAP calendar series with an `UNTIL`, read and written back.

## Capabilities moved

- offline-store (cairn/spec/offline-store.md): modified "The sync vectors run through the app's load and write" (format 8, the pin and the counts).
