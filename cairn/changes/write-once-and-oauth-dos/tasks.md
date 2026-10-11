---
cairn: tasks
change: write-once-and-oauth-dos
---

# Tasks

Rust paths are relative to rust/src/, Java to android/*/src/main/java/org/pimalaya/.

## M6 duplicate writes

- [x] client/smtp.rs: `Uncertain` after the final `.` (`handed`), a failed `QUIT` after acceptance ignored
- [x] client/throttle.rs: `Exchange.heard`, `Client::unanswered`
- [x] ffi/mail.rs: `Refused.uncertain`, `Refused::http`, `uncertain()`; JMAP, Graph and Gmail sends classified
- [x] client `SubmissionUncertain`, `PimalayaClient.submitMessage`
- [x] app `RemotePass.drainOutbox` parks uncertain; `MailStore.parkUncertain`, `resendOutgoing`, `StoredMessage.uncertain`; `MailList` row; `MessageView` *Send again*; strings en, fr
- [x] client/imap.rs: `copied` and `holds` before COPY in the no-MOVE relocation, before APPEND; mail.rs `message_id`
- [x] client/graph_calendar.rs: `create_body` with `transactionId`

## S2 L1 throttle

- [x] client/throttle.rs: `timed_out` never resent; 503 retried only for `idempotent` methods

## OAuth denial of service

- [x] `OauthFlow.handleRedirect`: `isRedirectOf` (exact redirect URI, state) before consuming; `expired`, `GRANT_TTL_MS` 15 min
- [x] `PendingGrantStore`: `savedAt`, abandoned grant dropped on load and open
- [x] `OauthFlow.launchSchemeGrant` calls `onBusy`

## S1 L2 loopback

- [x] `LoopbackRedirect.await`: per-connection 10 s timeout, 8 KiB line, path and state, 400 to the rest
- [x] `OauthFlow.startLoopbackOauth`: own thread, `127.0.0.1` only

## Tests

- [x] Rust: throttle (timeout not resent, 503 POST not resent, 429 POST resent, unanswered), smtp (uncertain after the dot, `handed`), ffi mail (`uncertain`), imap (relocation rerun after a lost COPY files one copy, first relocation copies, duplicate append skipped), graph_calendar (`transactionId` stable per object)
- [x] Robolectric: `PendingGrantTest` (state and URI checked, forged intents keep the grant, abandoned grants dropped, scheme grant busy), `LoopbackRedirectTest`, `MailStoreTest` (uncertain park and send again)

## Build

- [x] cargo fmt, clippy --all-targets (no warning), cargo test
- [x] gradle `:app:assembleDebug :app:testDebugUnitTest :client:testDebugUnitTest`
- [x] cairn/verify.sh
- [x] spec folded, log written, CHANGELOG

## Left

- [ ] Device check of the loopback and redirect paths (user's)
