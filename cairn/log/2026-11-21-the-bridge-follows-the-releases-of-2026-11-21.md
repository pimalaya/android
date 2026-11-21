---
cairn: log
change: the-bridge-follows-the-releases-of-2026-11-21
date: 2026-11-21
---

# The bridge follows the releases of 2026-11-21

The Rust bridge moves onto io-http 0.9.0, io-oauth 0.8.0, io-gcal 0.6.0, io-gmail 0.10.0, io-gpeople 0.9.0, io-gtasks 0.3.0, io-msgraph 0.10.0, io-jmap 0.11.0, io-webdav 0.13.0, io-pim-discovery 0.15.0 and io-pimdir 0.19.1. A dependency move, no proposal; cairn/log/2026-11-20-the-bridge-follows-io-pimdir-0-19.md is the model.

## The manifest

- **Features**: the io-* `client` feature now gates the I/O-free coroutines, the blocking client moving to `std` (family rule cargo-006). The bridge drives the coroutines with default features off, so io-gcal, io-gmail, io-gpeople, io-gtasks, io-http, io-jmap, io-msgraph and io-oauth name `client` beside their data features. io-webdav already named `client`, which now means the coroutines. io-pim-discovery's mechanism features imply `client`; the bridge named no `srv`. The dev-dependency io-pimdir keeps `client` and `vendored` (io-pimdir's features did not change).
- **Lock**: `cargo update -p` on those eleven crates moved them alone in rust/Cargo.lock.
- **Flake**: flake.nix pins `ioPimdirVersion` 0.19.1, its spec vectors fetched from the v0.19.1 tag with the new sha256.
- **Code**: no source change.

## The sync vectors

io-pimdir 0.19.1 adds sync vector 195-a-rekey-runs-the-flag-axis-on-its-listed-flags. The bridge runs no rekey, so android/app/src/test/resources/org/pimalaya/pimdir/sync-vectors.txt lists it `unbound`, beside the other rekey vectors.

## Evidence

`nix develop --command bash -c 'cd rust && cargo test --locked'` with the flake's v0.19.1 spec, 2026-11-21: 738 passed, 1 ignored. `cargo fmt --check` and `cargo clippy --locked --all-targets -- -D warnings`: clean. `gradle -p android :app:testDebugUnitTest`: before the vector was listed, 1066 tests, 1 failed (SyncVectorsTest, 195 cannot be bound), 5 skipped; after, 1066 tests, 0 failed, 5 skipped. `:app:assembleDebug`: green.

## Capabilities moved

None.
