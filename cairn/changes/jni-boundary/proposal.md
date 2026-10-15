---
cairn: change
id: jni-boundary
status: active
created: 2026-10-15
---

# The JNI boundary: one account, backend facts from Rust, less JSON

## Why

java-domain-layout typed the Java side and left the boundary itself as it was (its phase 8). Evidence and counts are in analysis.md.

- 21 natives take the account as positional `baseUrl, login, password`; Rust decodes it in 20 copy-pasted blocks, the domain clients splat it in 55 places.
- Java re-asks the backend and branches on its identity at 38 call sites in 14 files (`isGraph`, `isGoogle`, `isJmap`...), deciding things Rust already knows: whether mail appends, whether a reply marks `\Answered`, worker width, account-wide listing. Three predicates (`writes_overrides`, `writes_exdates`, `writes_events`) are true for every remote backend: they only mean "not local".
- The JSON left on the wire is the offline-driver yields, which Java edits in place to name messages (MailEngine, about 250 lines), the phone event view (Rust typed, Java reads JSONObject), and five text verbs whose errors Java sniffs by a leading `{`.
- Dead natives: the deleted advanced editor's five (rust/src/contact/props.rs, 409 lines), `discover`, `listAccountCards`.

## What

1. **Dead code.** The seven dead natives, their exports and props.rs go.
2. **Constant predicates.** `writes_*` go; Java asks whether the account is local.
3. **One account argument.** Every network native takes one `account` JSON string (`baseUrl`, `login`, `secret`, `address`, `submitUrl`) decoded once in rust/src/ffi; `multigetCards` and the token-only Graph/Google reads join it. Stateless: renewal stays "the next call carries the new secret", JMAP sessions stay cached in Rust. No Rust-held account handle.
4. **Backend facts, not identity.** `BackendInfo` carries capability facts (`appendsMail`, `keepsAnswered`, `accountWide`, `hasAllMail`, `sessions`, a protocol enum for the settings label); the 38 call sites read facts; the static memo in AccountClient goes. The contact type spinners are built from `cardTypeOrder` (today test-only), so arrays.xml stops restating Rust's TYPE sets.
5. **Store formats out of the UI.** `DeletedItems.Row.retainedAt` as epoch millis; AccountSettings' server description from Rust.
6. **Typed phone event view.** Java records for the PhoneEvent family; CalendarMapping off JSONObject, its logic unchanged.
7. **Uniform text replies.** The five text verbs and `updateEvent` answer `{ical}` / `{etag}` / an error object; the `{` sniffing goes.
8. **Naming in Rust.** `bound`, `pendingCreates` and `held` yields let the enumerate driver name members; MailEngine's naming code and the `enumerateMailbox` / `nameMessages` JSON exceptions go.

Every step lands on its own, behaviour-preserving, captured before and compared after (wire values equal as JSON), on the gate of java-domain-layout plus `cargo test` and `cargo clippy --all-targets`.

## Out of scope

- **Storage in Rust** (PimdirStorage and the hand-written SQL onto io-pimdir's `client` feature). It reverses spec/offline-store.md (the app takes io-pimdir without `client`) and would put two SQLite copies in one process, whose POSIX locks break each other. A spike first, its own change: does `PimdirStore` open a device store unchanged, can rusqlite link requery's library.
- **CalendarMapping rows and merge in Rust.** They compare civil times in the device zone against CalendarContract; Android-only, typed here, kept in Java.
- **Later changes**, each its own: contact fetch/push strategy in Rust, setup planning and endpoints in Rust, VTIMEZONE from ical-rs `tzdb` instead of `Zones.vtimezone` (bundled vs device tzdata), typing the load/write yields (only if storage stays Java).
- Token exchange and refresh carrying the RFC 8707 resource: network-trust-hardening L3.

## Decisions (user, 2026-10-15: all as recommended)

- D1. The account crosses as one JSON string, not a Rust handle or JNI field reads.
- D2. Facts only on the public client API, `isGraph`/`isGoogle`/`isJmap` gone.
- D3. Storage stays out of this change, spike later.
- D5. `cardTypeOrder` becomes production (spinners from Rust).
- D7. Naming moves into Rust (step 8): it deletes the JSON instead of typing it twice.
