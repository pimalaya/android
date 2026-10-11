---
cairn: change
id: write-once-and-oauth-dos
status: active
created: 2026-10-11
---

# Writes land once, and a stray intent cannot kill a grant

## Why

The 2026-10-11 audit left these open after network-trust-hardening and crash-and-surface-hardening:

- S2 M6: every write the app retries could land twice. A send whose answer was lost (SMTP after the final `.`, JMAP `EmailSubmission/set`, Graph `sendMail`, Gmail `messages.send`) read as transient and went again on the next drain: recipients got the mail twice. A failed `QUIT` after an accepted message did the same. On IMAP servers without MOVE the COPY-then-dispose fallback ran again inside the session's rerun and filed a second copy; a sent-copy `APPEND` whose answer was lost stayed staged and was appended again. A Graph event create carried no `transactionId`, so a lost answer created a second event and a second invitation.
- S2 L1: a 30 s read timeout counted as a lost connection, so a PUT still being processed was sent again, its 412 feeding the unguarded 412 retry; a 503 sent any method again, POST included, though a gateway may answer 503 after the work was done.
- Carry-over OAuth items 2 to 4: `handleRedirect` consumed the pending grant before checking the state, so any app sending a VIEW intent on the app's schemes (or a mail's link) killed an in-flight grant; `launchSchemeGrant` never showed the loader; an abandoned grant stayed on disk for ever.
- S1 L2: the custom-client loopback listener ran on the app's single io thread, read one line with no timeout and no bound, took the first local connection whatever it sent, and accepted a typed `localhost`, which a browser may resolve to `::1` where another app can listen.

## What

- Sends: a failure after the whole message was handed over and before a verdict (SMTP: the final `.` written; HTTP: the request written whole and no byte of the answer, or a 500, 502, 503 or 504) is *uncertain*. The bridge answers `uncertain`, the client throws `SubmissionUncertain`, the drain parks the row as such (`MailStore.parkUncertain`), and the row reads *May have been sent*. Nothing sends it again on its own: the reader offers *Send again*, which queues it anew (`MailStore.resendOutgoing`). Chosen over a server-side Message-ID check, which no SMTP server without its own sent filing and no Gmail API send (it rewrites the Message-ID) could answer. A failed `QUIT` after acceptance no longer fails the send.
- IMAP relocation without MOVE: the COPY is skipped when the target holds the message's `Message-ID` (`UID SEARCH HEADER`), so the session's rerun disposes of the original without a second copy. The sent-copy `APPEND` is skipped likewise.
- Graph event create: posts the event with a `transactionId`, the first 16 bytes of the object's SHA-256 in hex, the same for every attempt of one staged create.
- Throttle: a read timeout is never a lost connection (no resend); a 503 is retried only for an idempotent method.
- OAuth: an intent redeems the pending grant only when its URL is the grant's redirect URI exactly, up to its query, and carries the grant's state; anything else leaves the grant waiting. A grant older than 15 minutes is dropped, in memory, on load and when the store opens. `launchSchemeGrant` calls `onBusy`.
- Loopback: its own daemon thread; each connection gets a 10 s read timeout and a request line of at most 8 KiB; only `GET` of the redirect's path with the grant's state is taken, every other request answered 400 while the wait goes on; a typed redirect must name `127.0.0.1`.

## Edits outside this change's own files

- rust/src/client/graph_calendar.rs: `create_graph_event` posts through `create_body` (tiny, no overlap with the Google delete-loss change).
- `cargo fmt` reformats the whole crate.

## Out of scope

- Server-side Message-ID lookup before a resend (see above); the reader's *Send again* is the confirmation.
- A JMAP failure on a step before the submission (import, upload) that lost its answer also reads as uncertain: conservative, the user confirms.
- An edit staged between a lost Graph create and its retry changes the digest, so that rare retry may still create a second event.
