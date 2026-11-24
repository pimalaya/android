---
cairn: log
change: the-bridge-follows-the-releases-of-2026-11-24
date: 2026-11-24
---

# The bridge follows the releases of 2026-11-24

The Rust bridge moves onto io-pimdir 0.20.0, io-gpeople 0.9.1, io-jmap 0.11.1, io-msgraph 0.11.0 and io-webdav 0.14.0, all from crates.io. A dependency move, no proposal; cairn/log/2026-11-21-the-bridge-follows-the-releases-of-2026-11-21.md is the model.

## The manifest

- **Manifest**: rust/Cargo.toml requires the five releases, the dependency and the dev-dependency io-pimdir both at 0.20.0, and the `[patch.crates-io]` git pin of io-pimdir 17d4ea8 is gone with its section.
- **Lock**: `cargo update -p` on the five crates moved them alone; rust/Cargo.lock names no git source.
- **Flake**: flake.nix pins `ioPimdirVersion` 0.20.0 again, its spec vectors fetched from the v0.20.0 tag (spec REVISION dced98c, 221 sync vectors), and the shell compares it with the registry version the lock names.
- **deny.toml**: `allow-git` is empty again.

## What moved

- **A refused DAV member retires nothing.** io-webdav 0.14.0 returns the member rows a `sync-collection` or its `PROPFIND` fallback answered with an error other than 404 and 507 (a 403, a 5xx) as `unreadable`, where 0.13 dropped them. The drain (`Webdav::sync_collection`) keeps them across its pages and logs each, and a calendar or address book round holding one answers incomplete, as its doc asks, so the engine retires no member: before, an initial round with a refused member read complete and retired it. An incremental round keeps its token as before.
- **A JMAP event is created as a JSCalendar 2.0 object.** io-jmap 0.11.1 keeps the Group's `version: "2.0"` on an event converted from iCalendar, which Fastmail requires of a create. The bridge's test asserted it absent from the 2026-10-10 finding that Stalwart 0.16 refused it; the live-night probe of 2026-11-24 (journal 10:36) found the local Stalwart accepting it, so the test now asserts `"2.0"`.
- **A relocation lands its create on the flags its member carries.** io-pimdir 0.20.0's `PimdirWriteOp::LandPlacement` gains `flags`, the base of the member the relocation removed (sync-draft-15, vector 214). The wire's `landPlacement` carries them as `flags`, left out when unknown, and `PimdirStorage.land` binds them to rust/pimdir/queries/app/land_binding.sql, which based the landed binding on the item's flags: a flag change staged before a move answered by `COPYUID` was pulled back over instead of pushed.
- **Not ported, not needed.** io-pimdir 0.20.0's `rename_collection` rewrites queued submits' `copy` (`list_queued_submits`, `set_queued_payload`) and follows parked rows' targets; the app never renames a collection, so nothing runs them. Vectors 215 to 221 (copies, moves and adds over a tombstone, a fourth copy, a flag change on a delivered create) are io-pimdir's engine and mutate over the Java load and write, and pass unchanged.
- **The spec's JMAP line.** cairn/spec/calendar.md still said JMAP calendar writes were not written; it now says every backend takes writes, a JMAP one by `CalendarEvent/set` with `version: "2.0"` and no `method`, an edit patching only what differs under `ifInState`.

## Evidence

Pinned on CPUs 13 to 15 and 29 to 31 with the flake's v0.20.0 spec: `cargo fmt --check` clean; `cargo clippy --all-targets --locked -- -D warnings` clean; `cargo test --locked` 749 passed, 1 ignored; `cargo deny check` advisories, bans, licenses and sources ok. `gradle -p android :app:testDebugUnitTest --rerun-tasks`: 1,075 tests, 0 failures, 3 skipped; the sync vectors 221, 169 passed, 0 failed, 52 unbound, sync-vectors.txt unchanged. `:app:assembleRelease` (unsigned, the four ABIs by `cargo ndk`): green.

`a_drain_keeps_the_rows_it_could_not_read`, `contact::tests::a_first_round_with_a_refused_card_is_not_complete` and `calendar::tests::a_first_round_with_a_refused_entry_is_not_complete` failed before the drain kept the rows (`left: 0, right: 1`; "the refused card is not retired"). Vector 214 failed against the previous `land` (`sync vectors: 168 passed, 1 failed, 52 unbound`; its binding's base flags expected `[\Flagged]`, got the item's `[$Important, \Seen]`).

## Capabilities moved

- carddav-sync: new requirement "A DAV listing holding a refused member is incomplete".
- offline-store: "A relocation stating its member's handle lands its create" bases the landing on the flags the member carries, with a scenario; the vectors' counts at 0.20.0.
- calendar: the line on writes names every backend, the JMAP one included.
