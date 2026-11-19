---
cairn: tasks
change: release-pinned-libs
---

# Tasks

Closed 2026-11-19 by the owner: the releases came through the family release rounds, and io-http's default bound stands in for a per-call one.

## Releases (in the libraries)

- [x] io-http released (0.8 in use)
- [x] ical-rs released (0.7.1 in use)
- [x] io-imap released (0.11.2 in use)
- [x] io-pimdir released (0.17.0 in use)
- [x] io-webdav released (0.11 in use)
- [-] A per-call `max_body_len` in io-webdav, io-jmap, io-gmail, io-gcal, io-gpeople, io-msgraph: not needed now, io-http 0.8 bounds every buffered answer at 256 MiB by default; reopened as a family change if an out-of-memory crash is reported

## App

- [x] rust/Cargo.toml on released versions, no `[patch.crates-io]` git entry, no git source in Cargo.lock
- [-] Bridge `max_body_len` per call: see above, the library default applies
- [x] io-jmap, io-msgraph, io-pim-discovery released, their patches gone
- [x] Spec folded (spec/network-trust.md), log written, change archived
