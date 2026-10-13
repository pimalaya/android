---
cairn: tasks
change: rust-domain-layout
---

# Tasks

Paths are relative to rust/src/. Every step ends with `cargo test`, `cargo clippy --all-targets` (0 warnings), `cargo fmt`, the exported JNI symbols diffed against the baseline (empty), and, at the end of each phase, `:app:assembleDebug :app:testDebugUnitTest`.

## Phase 0: baseline

- [x] 499 Rust tests, 0 clippy warnings, 102 exported symbols recorded
- [x] Gradle baseline green: assembleDebug, 531 JVM tests

## Phase 1: moves only

- [x] error.rs: `BridgeError` out of types.rs, `coroutine_error`/`http_status` out of client/convert.rs
- [x] contact.rs + contact/: card types, card half of client/dispatch.rs, carddav, msgraph (client/graph.rs), gpeople (client/google.rs), jmap (contact half of client/jmap.rs and jmap.rs), push (push helpers of client/convert.rs), sync (store.rs), fields (project.rs), form, props, dedup, merge, group
- [x] calendar.rs + calendar/: calendar types, event half of client/dispatch.rs, caldav, msgraph (client/graph_calendar.rs), gcal, jmap (calendar half of client/jmap.rs and jmap.rs), read/edit/expand out of calendar.rs
- [x] mail.rs + mail/: mail types, compose and read (mail.rs), listing, session (logic of ffi/session.rs), imap, smtp, gmail, gmail_step, msgraph (client/graph_mail.rs), jmap, jmap_step
- [x] account.rs + account/: discovery and oauth (client/discovery.rs, oauth.rs)
- [x] client.rs + client/: throttle, trust, webdav (client/sync_collection.rs), jmap and msgraph shared plumbing
- [x] offline.rs + offline/: wire, summary; ffi/pimdir.rs into ffi/offline.rs
- [x] ffi/: account, contact, calendar, mail, offline
- [x] no `#[path]`, no `super::`, no test-only item in production code (`offline::wire::enumerated` moved into its tests, `mail::read::decode_header` and its three tests deleted, the compose round trip parsing with mail-parser)
- [x] types.rs, client/convert.rs, client/dispatch.rs, project/ gone

## Phase 2 and 3: structured types and one test seam

Order: Auth and Account, then the Host seam (so backend structs carry `Client<H>`), then the rest.

- [x] `Auth { Basic, Bearer }` replaces `Credentials`, `Account` built once per export, routers take `&Account`
- [x] `Host` trait (transport, driver `serve`, clock), `Client<H: Host>`, JNI implementation in ffi/, `Wire` and `ImapWire` gone
- [x] per-backend structs holding client, endpoint and credential
- [x] `Contacts`, `Calendars` and `Mail` routers with params structs, no `too_many_arguments`
- [x] `offline::Driver` methods taking options structs
- [x] serde-typed contact sync facts, `ContactFields` and the form, merge, group, props, dedup replies, calendar, mail, offline and account replies (byte-identical, captures in the session scratchpad)
- [x] per-backend fake traits and `Live*` adapters replaced by a fake HTTP host (client/fake.rs); `Host` is the only trait left
- [x] fixed on the way: a JMAP blob download refused with 401 now carries its status, so a mail step fails for the token to be refreshed instead of logging every body as failed (found by the HTTP-level fakes)

## Phase 4: docs

- [x] lib.rs architecture header (header-001, header-004)
- [x] module titles and ceilings (inline-001 to inline-008): every module titled, no untagged comment, every item doc within the ceiling but `# Safety` sections, link definitions and `decode_header`; cargo doc warning-free
- [x] docs/contacts-mapping.md and docs/calendar-mapping.md name the new paths; docs/hub-decomposition-plan.md and docs/refactor-plan.md marked superseded for their Rust parts
- [x] Cargo.toml per cargo-001/009 (section order, strip, NOTE keeping unwinding); cargo-008 left for `jni`, `percent-encoding`, `rand` (default features, a build decision of its own)

## Phase 5: upstream

- [x] io-jmap `vcard` and `ical` features (io-jmap 3d25e2b, pinned by git rev): `JmapContactCard::to_vcard`, `from_vcard`, `update_from_vcard`, `JmapCalendarEvent::from_ical` over a parsed calendar (the app keeps its nesting guard), `JmapCalendarEventPatch::diff`; 391 cases byte-identical, 7 tests moved upstream
- [x] JMAP revision hash pinned to SipHash-1-3 with zero keys (siphasher), the values `DefaultHasher` gave, pinned by a test: sha2 would have changed every stored revision and turned staged `If-Match` stale
