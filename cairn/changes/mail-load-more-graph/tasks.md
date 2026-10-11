---
cairn: tasks
change: mail-load-more-graph
---

# Tasks

Java paths are relative to android/app/src/main/java/org/pimalaya/.

## Bridge

- [x] io-msgraph pinned at 2ae2637 (`$batch` inner bytes, `Retry-After`)
- [x] graph_mail.rs: `GraphMail` (dated listing, `$batch` of `$value`, a lone GET, sizes, pause), `step` (keyset walk, ties, held at F, roster, per-folder fallback merged), `read_sources` (20 and about 4 MB a batch, 1 MB or unknown alone, 429 once, undecoded alone); `Band.skip` and `Ceiling.skip` go
- [x] ffi/mail.rs: `mail_step` Graph branch (`{count, held, frontier, bound, mailboxes}` in, `{exhausted, items}` out, each `{handle, mailboxes, sortKey, received, path?, size?}`); `fetch_sources` Graph branch

## App

- [x] `GraphStep`; `MailStep.of` picks it for Graph
- [x] `MailEngine.reach` (a folder listed down to a date unless its coverage reaches it), `MailEngine.stepRequest` (the request every backend shares, the frontier a folder's own over it alone), `MailEngine.stepped(mailbox, request, dir)`; `PimalayaClient.mailStep(session, mailbox, request, dir)`, the overloads gone

## Tests

- [x] Rust (graph_mail_tests.rs): the walk (keyset, held at F, ties, roster, exhausted, a crowded second, fallback merge), the batches (grouping, alone, 429 once, undecoded, gone and failed left out)
- [x] Java: `GraphStepTest` (a first step lands headers by band and bodies from the step's files, a later step from the frontier, out-of-scope folders)

## Land

- [x] CHANGELOG [Unreleased] as a net diff
- [x] Build: `cargo test`, `cargo fmt`, `cargo clippy`, `:app:assembleDebug`, `:app:testDebugUnitTest`; cairn/verify.sh
- [x] Fold the delta into spec/mail.md, log entry
- [ ] Live: a `$value` inside `$batch` answered as a base64 string, on microsoft@pimalaya.onmicrosoft.com (the secret's pinentry timed out)
- [ ] Device test on a Microsoft 365 account
- [ ] Archive once the device test passes

## Known waste

io-pimdir's `size_agrees` compares the MAPI `PR_MESSAGE_SIZE` a Graph listing states with the MIME length of a body held under the same link id, so such a body is never linked into another copy. A stepped message's own summary takes the MIME length once its body lands (`MailStore.restateFromBody`), and the step answers the MIME length as its size; a copy listed later still states the MAPI one. Fixing it is io-pimdir's (a size that may disagree on Graph) or a listing that never states the MAPI size.
