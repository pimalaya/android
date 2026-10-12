---
cairn: tasks
change: body-loss-races
---

# Tasks

Java paths are relative to android/app/src/main/java/org/pimalaya/, tests to android/app/src/test/java/org/pimalaya/.

## Bodies

- [x] `PimdirStorage.applyUpsert`: a mail upsert carrying no object below the row's level keeps the body, `writeBinding` its base
- [x] `PimdirItems.pin`/`unpin`; `unlinkCollected` in a transaction of its own, a pinned hash re-deferred
- [x] `MailEngine.stored` pins before adopting; `download` releases and defers once the upgrade returned
- [x] `PimdirQueue.enqueue` files its body inside its transaction
- [x] `PimdirStorage.storeObject`: a persisted body missing its file refused

## A message gone

- [x] rust/src/client/convert.rs: `converged`
- [x] Gmail (rust/src/client/gmail.rs) and Graph (graph_mail.rs) item writes converge on 404, a copy keeps it
- [x] JMAP (jmap.rs): `email_set_refusal` answers `notFound` as 404, converged but for a copy
- [x] `MailEngine` push: `gone`, a removal, move or marker accepted, a copy refused

## Tests

- [x] Rust: `a_write_on_a_message_gone_converges`, `a_write_failing_otherwise_still_fails` (gmail_tests), `an_email_set_on_a_message_gone_answers_404_for_the_caller_to_converge` (jmap_tests)
- [x] Java `BodyLossRacesTest`: a stale write keeps the reader's body and a fill's base; a pinned body outlives the collector; a download racing the last reference keeps its file; a persisted body missing its file is refused
- [x] Java `MailMoveTest`: a delete, a move of a message gone accepted and the page landing; a copy of one refused

## Land

- [x] Spec lines in cairn/spec/offline-store.md and cairn/spec/mail.md, log entry
- [x] CHANGELOG [Unreleased] Fixed
- [x] Build: `cargo fmt`, `cargo clippy --all-targets`, `cargo test`, `:app:assembleDebug`, `:app:testDebugUnitTest`, `:client:testDebugUnitTest`, `:app:minifyReleaseWithR8`; cairn/verify.sh
- [x] Live: e2e on google-gmail, google-imap (2026-10-12, 16 passed, none failed)
