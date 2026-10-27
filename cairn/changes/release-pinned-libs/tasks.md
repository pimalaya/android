---
cairn: tasks
change: release-pinned-libs
---

# Tasks

Paths are relative to the repository root unless they name a library.

## Releases (in the libraries)

- [ ] Decide io-http 0.5.1 vs 0.6.0; release it from e181530 or later
- [ ] ical-rs 0.7.0 from caa1422 or later (`TooDeep` is breaking); optionally first make `IcalRecurExpand::skip_to` jump for a COUNT rule too (ical src/recur/expand.rs:203 returns early when `count` is set, so a counted series still walks every occurrence)
- [ ] io-imap release from b1ab38e or later (fetch batch options are breaking), abnf-core kept at 0.6.0
- [ ] io-pimdir 0.8.0 from 189db40 or later
- [ ] io-webdav release from 62fad89 or later (`parse_multistatus` answers a `Result`)
- [ ] io-webdav, io-jmap, io-gmail, io-gcal, io-gpeople, io-msgraph: a per-call `max_body_len` option handed to io-http (default unchanged)

## App

- [ ] rust/Cargo.toml: requirements raised to the releases, the ical-rs, io-http, io-imap, io-pimdir and io-webdav `[patch.crates-io]` entries dropped (lines 62 to 67, and the NOTE at 61); Cargo.lock refreshed, no git source left for them
- [ ] Bridge: `max_body_len` set on every HTTP call, 64 MiB for an answer read whole, a body download its own bound; a test that an answer past the bound fails as a server error, nothing held
- [ ] io-jmap (3d25e2b), io-msgraph (2ae2637), io-pim-discovery (6ceeaf4): released and their patches dropped too, or kept with a reason before the store release
- [ ] Build: cargo fmt, clippy, test; `:app:assembleDebug :app:testDebugUnitTest :client:testDebugUnitTest`; `cargo deny check`; cairn/verify.sh
- [ ] Fold, log, CHANGELOG
