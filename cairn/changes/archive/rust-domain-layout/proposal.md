---
cairn: change
id: rust-domain-layout
status: landed
created: 2026-10-13
---

# The bridge crate laid out by domain, typed, and tested through one seam

## Why

The rust/ crate is hard to navigate. Its three domains (contacts, calendars, mail) are spread over client/ (one file per backend, client/jmap.rs mixing all three), project/, the crate root and ffi/. types.rs is a catch-all (naming-005). Internal functions take up to nine arguments that are missing structs, and two hubs (project.rs, store.rs) run on untyped `serde_json::Value`. About sixteen traits exist only so a test can fake them, with `Live*` adapters in production and nine banned `#[path]` test files (naming-002). Docs miss the module titles inline-001 asks for and often exceed its ceilings.

## What

Behaviour-preserving restructuring, in phases, each leaving the crate, the Rust suite, the app build and its JVM tests green, and the exported JNI symbols unchanged.

1. Moves only: three domain modules (`contact`, `calendar`, `mail`) plus four cross-cutting ones (`account`, `client`, `offline`, `ffi`), each backend file named after the io- crate it drives, types next to their owners, tests at the end of their module (or in a child `tests` module when large), `crate::` instead of `super::`.
2. Structured types: `Auth` and `Account`, per-backend structs holding their client, endpoint and credential, per-domain routers taking params structs, serde-typed facts and field model with byte-identical JSON.
3. One test seam: `Client` generic over a `Host` trait (everything Java provides), replacing the per-backend fake traits.
4. Docs aligned with the guidelines (header, inline).
5. Upstream: the JMAP vCard and JSCalendar conversions move into io-jmap behind `vcard` and `ical` features; the JMAP revision hash is pinned (same values, stable across toolchains).

The JNI signatures and the Java side stay as they are; collapsing the positional JNI arguments is a later Java pass.

It supersedes the Rust parts of docs/hub-decomposition-plan.md and docs/refactor-plan.md.
