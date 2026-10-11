---
cairn: tasks
change: mailbox-refusal-skipped
---

# Tasks

Java paths are relative to android/app/src/main/java/org/pimalaya/, tests to android/app/src/test/java/org/pimalaya/.

## Bridge

- [x] rust/src/client/imap.rs: `examine` and `select` read the outcome through `attempt`, a `NO` answered with `MAILBOX_REFUSED` (404)

## App

- [x] `MailEngine.refused` and `MailEngine.skip`
- [x] `ImapStep.opening`: a refused mailbox left out of the account floor and of the listing, every one refused failing the step
- [x] `WidenStep`: a refused mailbox widened no more, the step not reporting the account whole then; the first step's listings likewise
- [x] `RemotePass.fetchMail`: a refused mailbox skipped, every one refused failing the pass

## Tests

- [x] Rust (scripted IMAP server): Gmail's, Exchange's and RFC 5530's refusals answer 404, a `BYE` does not
- [x] Java (`ImapStepTest`): a first step skips a refused mailbox; every mailbox refused, or a failure that is no refusal, fails it; a later step widens past a refused mailbox

## Land

- [x] Spec line in cairn/spec/mail.md, log entry
- [x] CHANGELOG [Unreleased]
- [x] Build: `cargo fmt`, `cargo clippy --all-targets`, `cargo test`, `:app:assembleDebug`, `:app:testDebugUnitTest`, `:app:minifyReleaseWithR8`; cairn/verify.sh
- [x] Live: e2e on google-gmail, google-imap, microsoft-imap, fastmail-jmap, fastmail-imap, fastmail-caldav, fastmail-carddav (2026-10-11, 41 passed, none failed)
