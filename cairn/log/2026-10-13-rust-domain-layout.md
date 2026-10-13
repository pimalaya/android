---
cairn: log
change: rust-domain-layout
landed: 2026-10-13
---

# The bridge crate laid out by domain, typed, and tested through one seam

Capabilities moved: none in requirements. mail (fixed: a JMAP blob download refused with 401 now carries its status, so a mail step or source read fails for the token to be renewed, as spec/mail.md already required, where every body was logged as failed).

rust/src is regrouped by domain: `contact`, `calendar` and `mail` each own their types, a router (`Contacts`, `Calendars`, `mail::session::Mail`) dispatching on the account's backend, and one file per backend named after the io- crate it drives; `account` (backends, discovery, OAuth), `client` (transport policy, shared protocol plumbing), `offline` (io-pimdir engine driver and wire mirrors), `ffi` (JNI exports, marshaling only) and `error` cut across them. types.rs, project/, store.rs, summary.rs, jmap.rs and client/dispatch.rs are gone; no `#[path]`, no `super::`, no re-export.

Signatures are structured: `Auth { Basic, Bearer }` replaces the empty-login convention, an `Account` is built once per export, backends are structs holding their client and credential (`Webdav`, `Gpeople`, `Gcal`, `Gmail`, `Msgraph`, `Jmap`), operations of three or more inputs take params structs, and no `too_many_arguments` allowance is left. `Client` is generic over a `Host` trait (transport, `OfflineDriver.serve`, clock) whose JNI implementation lives in ffi/host.rs, so client/ and offline/ hold no JNI. The JSON crossing JNI is typed (contact sync facts, `ContactFields` and the form, merge, group, props and dedup replies, the calendar, mail, offline and account replies), byte-identical on every input Java produces, fields declared in alphabetical order of their keys, the order serde_json's sorted maps always gave and Java compares printed models in.

The fifteen per-backend traits that existed only for test fakes are gone: tests drive the real backend structs through the real client over client/fake.rs, a host answering HTTP from each test's model of the provider; `Host` is the only trait left. The lib.rs header is the crate's architecture document, every module is titled and every comment tagged, item docs are within the inline-002 ceiling, and `cargo doc --document-private-items` is warning-free. The JMAP vCard and JSCalendar conversions moved into io-jmap behind its `vcard` and `ical` features (io-jmap 3d25e2b, pinned by git rev), the iCalendar one over an already parsed calendar so the app's nesting guard stays; the JMAP revision hash is pinned to SipHash-1-3 with zero keys, the values `DefaultHasher` gave. The Java side and the exported JNI symbols are unchanged; 490 Rust tests (seven moved upstream, three deleted with the test-only `decode_header`, one pinning the revision hash added) and 531 JVM tests pass.
