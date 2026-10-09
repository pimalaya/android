---
cairn: tasks
change: jmap-release-readiness
---

# Tasks

- [x] io-jmap: typed core capability, upload/download URL resolution, `Display` for per-object errors, the missing submission refusals, and `null` create maps read as empty (found by the live check); CHANGELOG [Unreleased]; its gates
- [x] Bridge: io-jmap through a `[patch.crates-io]` path entry
- [x] Capabilities: a native reading the session's served capability URNs; `PimDomain` maps them to domains
- [x] Probe per domain on the password paths (standard and sign-in page) and after an OAuth grant; a domain not served dropped alone and said so
- [x] Ranking per domain: JMAP calendars below CalDAV
- [x] JMAP send: identity, upload, import to Drafts, submission with envelope and `onSuccessUpdateEmail`; refusals mapped
- [x] `submitsOverSession` trait: compose gate, sent copy staged, account settings, sent-copy push; no `isJmap` left on those paths
- [x] Calendar listing paged, never complete when capped
- [x] Contacts: query paged, delta get chunked by `maxObjectsInGet`
- [x] `writesEvents` trait; JMAP calendars not writable; add, save and delete refuse; staged edits refused for good (422) on add, update and remove
- [x] `Email/set` `notFound` converged; session cache; readable refusals
- [x] Tests: Rust over fake JMAP calls (send, refusals, calendar paging, contact paging, session cache); JVM for the ranking and the capability decisions
- [x] Build: `:app:assembleDebug`, `:app:testDebugUnitTest`, `cargo test`, clippy, fmt; io-jmap gates; `cairn/verify.sh`
- [x] Live check on the Fastmail test account: one mail to itself, its copy in Sent without `$draft`, contacts listed (through a scratch replica of the bridge's calls over io-jmap's std client, the bridge itself running only on a device; the mail-scoped token serves mail and submission, the contacts token contacts, neither calendars)
- [ ] Device test: a Fastmail setup end to end
- [ ] Fold the delta into the specs, log, CHANGELOG
