---
cairn: change
id: release-pinned-libs
status: active
created: 2026-10-18
---

# Released libraries, no git revisions, a bound on what a server sends

## Why

The 2026-10-11 audit fixes landed in the libraries and reach the app only through git revisions in rust/Cargo.toml (`[patch.crates-io]`, lines 58 to 67, pinned on 2026-10-12 in 5a44a09): ical-rs caa1422, io-http e181530, io-imap b1ab38e, io-pimdir 189db40 and io-webdav 62fad89, beside io-jmap 3d25e2b, io-msgraph 2ae2637 and io-pim-discovery 6ceeaf4. None of these revisions is in a release: ical's last tag is v0.6.1 (caa1422 is the commit after it), io-imap's v0.7.1 predates b1ab38e, io-pimdir's last tag is v0.7.0 while the app asks 0.8. Unreleased code ships in the app, and an F-Droid or offline build needs GitHub to resolve it.

io-http 0.5 bounds a response body by `max_body_len` (256 MiB by default, `Http10Send::max_body_len`), but the bridge never sets it, and io-webdav, io-jmap, io-gmail, io-gcal, io-gpeople and io-msgraph give no way to: a hostile or broken server can make the bridge hold 256 MiB for one answer (audit S3-8).

## What

1. Release each library carrying the revision the app pins, by the semver its changes call for:
   - ical-rs: 0.7.0, `TooDeep` (nesting capped at 16) being a new error variant; it also carries recurrence `skip_to` and the two merge idempotence fixes.
   - io-http: 0.5.1 or 0.6.0, still to decide (new error variants, strict semver says 0.6.0 and eight libraries then need a bump; 0.5.1 reaches the app by `cargo update`).
   - io-imap: its next minor, `ImapMessageFetchStreamBatch::new` now taking options (`uid`, `max_literal_len`, `sizes`); greeting literal errors, bounded fetch lines and literal sizes, no credentials in logs. Keep abnf-core at 0.6.0 (0.6.1 drops the nom `alloc` feature imap-codec alpha.9 needs).
   - io-pimdir: 0.8.0 (the app already asks `0.8`).
   - io-webdav: its next minor, `parse_multistatus` now answering a `Result` (`MULTISTATUS_MAX_DEPTH` 64).
2. Move rust/Cargo.toml onto those releases and drop their `[patch.crates-io]` entries, the lock then naming no git source for them.
3. Expose `max_body_len` per call in io-webdav, io-jmap, io-gmail, io-gcal, io-gpeople and io-msgraph, and have the bridge set it: 64 MiB for an answer read whole (JSON, a multistatus, a card or an event), a message body keeping a bound of its own.

## Out of scope

- io-jmap, io-msgraph and io-pim-discovery stay on their git revisions here; listed as a last task so the store release carries no git source.
- neverest pins the same revisions (neverest Cargo.toml, `[patch.crates-io]` lines 84 to 90) and moves on its own.
- The libraries' CI and deny findings: release-supply-chain, and io-imap outside this repository.
