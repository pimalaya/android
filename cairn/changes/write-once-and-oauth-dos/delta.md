---
cairn: delta
change: write-once-and-oauth-dos
---

Folds into `spec/mail.md` (sends, IMAP writes, throttling, dead connections), `spec/calendar.md` (Graph creates) and `spec/onboarding.md` (the OAuth redirect and the loopback).

## ADDED Requirements

### Requirement: A send that may have gone waits for the user
A submission that fails after the whole message was handed over and before any verdict came back SHALL be uncertain: over SMTP once the data's final `.` was written, over JMAP, Graph or Gmail once the request was written whole and no byte of its answer came, or when a 500, 502, 503 or 504 answered. An uncertain submission SHALL park its row, shown as *May have been sent*, and SHALL never be handed over again on its own. The reader SHALL offer *Send again* on it, which queues the same message anew at the end of the queue and drops the parked row. (spec/mail.md)

#### Scenario: The connection dies after the final dot
- GIVEN an SMTP server that closes the connection after the message's final `.`
- WHEN the sync drains the queue
- THEN the row is parked, reads *May have been sent*, and the next sync hands nothing over

#### Scenario: The user sends it again
- GIVEN a message parked as *May have been sent* that the recipient never got
- WHEN the user opens it and taps *Send again*
- THEN it is queued again and the next sync hands it over

### Requirement: An IMAP copy or append is not made twice
Where the server has no MOVE, a relocation SHALL search the target for the message's `Message-ID` (`UID SEARCH HEADER`) before its COPY and skip the COPY when it is there, so a relocation run again after a lost answer disposes of the original without filing a second copy. An `APPEND` of a message whose `Message-ID` the mailbox already holds SHALL be skipped and count as done. A message carrying no `Message-ID` SHALL be copied and appended as before. (spec/mail.md)

#### Scenario: A COPY whose answer was lost
- GIVEN a server without MOVE whose connection dies after a COPY into Archive and before its answer
- WHEN the relocation runs again on a new session
- THEN Archive holds one copy and the original is disposed of

### Requirement: A Graph event is created once
A Graph event create SHALL carry a `transactionId`, the hex of the first 16 bytes of the SHA-256 of the iCalendar object, the same for every attempt of one staged create, so a create sent again after a lost answer lands on the event the first made and invites nobody twice. (spec/calendar.md)

#### Scenario: A create whose answer was lost
- GIVEN an event with attendees created on Graph, the answer lost
- WHEN the next pass pushes the staged create again
- THEN it names the same transaction, and Graph keeps one event

### Requirement: An OAuth redirect is the grant's own
An intent SHALL redeem, and so end, the in-flight grant only when its URL is the grant's redirect URI exactly, up to its query, and its query carries the grant's state; any other intent reaching the app SHALL leave the grant waiting. A grant started more than 15 minutes ago SHALL be abandoned: dropped when a redirect arrives, when it is loaded and when the app starts. A grant over the app's own scheme SHALL show the step's loader while the browser is out. (spec/onboarding.md)

#### Scenario: Another app fires the redirect scheme
- GIVEN a grant waiting in the browser
- WHEN another app or a link in a message opens `org.pimalaya:/oauth2redirect?code=x&state=forged`
- THEN nothing is redeemed and the real redirect still completes the grant

#### Scenario: A grant left in the browser
- GIVEN a grant started and never finished
- WHEN the app starts 20 minutes later
- THEN the sealed grant is gone

### Requirement: A custom client's loopback waits for its own redirect
The loopback redirect of a custom client SHALL be `http://127.0.0.1` (RFC 8252 section 8.3), a typed `localhost` refused. Its listener SHALL run on a thread of its own, give each connection 10 s and 8 KiB to send its request line, answer 400 to any request but a `GET` of the redirect's path carrying the grant's state, and keep waiting for that one until its 300 s pass. (spec/onboarding.md)

#### Scenario: A local app connects first
- GIVEN a custom-client grant waiting on its loopback port
- WHEN another app connects and sends nothing, then the browser lands
- THEN the silent connection is dropped after its timeout and the browser's redirect completes the grant, the app's sync never held

## MODIFIED Requirements

### Requirement: A message is submitted and a copy is kept
A sync draining the queue SHALL take an account's pending actions in append order and hand each message's bytes to the account's submit endpoint with the envelope its own address headers name, the `Bcc` among them, that header leaving the bytes on the way out so no copy a recipient receives names a blind one. A Graph or Gmail account SHALL hand the bytes, `Bcc` still in them, to `sendMail` or `messages.send` on the session it reads from: the API reads the recipients off the headers, keeps the blind ones from the recipients, and files the sent copy itself, in `Sent Items` or under `SENT`. A refusal for good is a 400, 403, 404, 413 or 422 there.

The submission SHALL file no copy itself: the copy is the create staged in the sent mailbox beside it (A sent message is in Sent at once), carried out by a push of its own. On IMAP that push SHALL `APPEND` the message as composed, already `\Seen`, its `Bcc` kept as the sender's record of whom it went to, and SHALL wait while the submission is queued or parked: a copy filed for a message that was not sent is a lie the sender reads as a sent message. A copy that could not be filed SHALL stay pending for the next sync and SHALL NOT be a reason to submit again. An account marking no sent mailbox SHALL send anyway, with no copy staged.

The row SHALL be removed by cancelling it once the message has been handed over, and never claimed before: a submission's effect is not a store mutation, so there is nothing to apply, and a claim that deleted the row before the server accepted the message would lose the message. A drain interrupted between the handover and the cancel SHALL send the message again; a failure after the handover SHALL NOT (A send that may have gone waits for the user), and an SMTP `QUIT` failing after the message was accepted SHALL NOT fail the send.

A submission the server refuses for good, which is a 5yz reply (RFC 5321 section 4.2.1), SHALL park the row with what the server said, counted as one attempt, shown as failed to send and never retried on its own; the drain SHALL carry on to the rows behind it. An uncertain one SHALL be parked as such and stop the drain. Any other failure SHALL count an attempt, leave the row pending and stop the drain, the usual cause being that there is no network and the next message would fail too. An action of a kind this app does not carry out SHALL be left pending and untouched, its attempts unbumped.

#### Scenario: The next sync
- GIVEN a queued message and an IMAP account with a submit endpoint and a `\Sent` mailbox
- WHEN the sync drains the queue and pushes
- THEN it is handed over, the row is cancelled, and the staged copy is appended to the sent mailbox

#### Scenario: A submission the server refuses
- GIVEN a server answering the envelope with a 5yz reply
- WHEN the sync drains the queue
- THEN nothing is appended anywhere, the row is parked carrying the error, and the message shows as failed to send

#### Scenario: One refusal among several
- GIVEN two queued messages, the first of which is refused
- WHEN the sync drains the queue
- THEN the second is still handed over

#### Scenario: No network
- GIVEN a queued message and no network
- WHEN the sync drains the queue
- THEN the attempt is counted, the message stays queued, and the drain stops there

#### Scenario: The copy cannot be filed
- GIVEN a submission the server accepted and a `\Sent` mailbox that refuses the `APPEND`
- WHEN the sync pushes the copy
- THEN the submission's row is cancelled anyway, nothing offers the message again, and the copy stays pending for the next sync

### Requirement: An HTTP sync rides out throttling
Every HTTP backend (Graph, Gmail, Google Calendar and People, CalDAV, CardDAV, JMAP) SHALL send a request again when the server answers 429, 503, or 403 with Google's `rateLimitExceeded` or `userRateLimitExceeded`, after the `Retry-After` the server named or the instant Google's message names (`Retry after …`), or else after an exponential back-off with jitter from about a second. A 503 SHALL be retried only for an idempotent method (GET, HEAD, OPTIONS, TRACE, PUT, DELETE, PROPFIND, REPORT): a gateway may answer it after the server behind did the work, so any other request SHALL take the 503 as its answer. Only an answer naming a per-minute quota (`Quota exceeded for quota metric … per minute per user`) SHALL wait longer: until the next minute, except on Gmail, whose quota refills by the second, where it SHALL wait 2 s, then a second more each time up to 5 s, never to the next minute and without holding the account's pacing; a rate or concurrency refusal (`Too many concurrent requests for user`, `User-rate limit exceeded`) SHALL NOT. An answer naming a daily quota SHALL NOT be retried. The waiting SHALL be bounded per request and per native call, a `Retry-After` past the bound SHALL NOT be waited, and past the bound the throttled answer SHALL fail the request as any error does. An answer whose end cannot be told SHALL NOT be retried. Every wait SHALL be logged at debug with the answer's status and reason.

#### Scenario: Graph asks for a pause
- GIVEN a Graph request answered 429 with `Retry-After: 2`
- WHEN the bridge reads the answer
- THEN it waits two seconds and sends the request again, and the sync goes on with the answer to that

#### Scenario: Gmail's concurrency limit
- GIVEN a Gmail read answered 429 `Too many concurrent requests for user`
- WHEN the bridge reads the answer
- THEN it sends the read again within a second or two, never waiting for the next minute

#### Scenario: Gmail's minute quota
- GIVEN a Gmail request refused twice for its per-minute quota
- WHEN the bridge reads the answers
- THEN it waits 2 s, then 3 s, and sends it again

#### Scenario: A server that keeps throttling
- GIVEN a server answering 503 to every request
- WHEN a sync pass reaches it
- THEN the request fails with the 503 within the bound, and the pass reports it as it reports any error

#### Scenario: A gateway's 503 to a send
- GIVEN a Graph `sendMail` answered 503
- WHEN the bridge reads the answer
- THEN it does not send the message again, and the send is uncertain

### Requirement: A dead connection is dropped, never read again
The transport SHALL drop an origin's connection on any read or write failure, a read timeout included, and at the end of its stream, and the bridge SHALL tell it to on any transport error, on every backend. Before a new HTTP request on a held connection, the connection SHALL be dropped unless the last answer on it was read to its end by its framing (`Content-Length` or chunked) and did not say `Connection: close`, so no answer, late or cut short, is ever read as another request's. A chunk size that is not bare hex digits, or names more than 64 MiB, SHALL count as framing that cannot be told: the answer passes through untouched and its connection is not reused. A reused connection that ends or fails before the first byte of an answer to an idempotent request (GET, HEAD, OPTIONS, PROPFIND, REPORT, PUT, DELETE) SHALL be reopened and the request sent again once (RFC 9112 section 9.3.1); a read that timed out SHALL NOT, the server being perhaps still at work on it, and any other request SHALL fail. A verb on a held session that fails after its connection was lost SHALL be run once more on a reopened session, an IMAP one greeted and authenticated again; a verb a live server refuses SHALL NOT be run again.

#### Scenario: Graph after two idle minutes
- GIVEN a Graph session idle for two minutes, its connection closed by the server
- WHEN the body step reads the next message
- THEN the read goes out again on a new connection, and the body is stored

#### Scenario: An answer that came too late
- GIVEN a read that timed out before its answer arrived
- WHEN the next request goes out
- THEN it goes on a new connection, and the late answer is never read as its own

#### Scenario: A slow PUT
- GIVEN a CalDAV PUT whose answer takes longer than the read timeout
- WHEN the read times out
- THEN the PUT fails and is not sent again

#### Scenario: A dead IMAP connection
- GIVEN an IMAP session whose socket the server closed
- WHEN the next verb runs on it
- THEN the session is opened again and the verb answers

### Requirement: An in-flight OAuth grant never sits on disk in clear
The app SHALL persist a grant that goes through the browser only sealed under the same Keystore key as the accounts, client secret, CSRF state and PKCE verifier included, and SHALL abort the grant rather than persist it in clear. It SHALL drop the persisted grant once its own redirect arrives (An OAuth redirect is the grant's own), before the code is exchanged, when the grant aborts, when the user leaves or steps back in the connection flow, and once it is 15 minutes old. A grant an older version persisted in clear SHALL be wiped when the app next starts, and one that no longer decrypts SHALL be dropped.

#### Scenario: The process dies in the browser
- GIVEN a Google grant started, the app reclaimed while the user signs in
- WHEN the redirect brings the app back
- THEN the grant is restored from its sealed copy and dropped from disk before the exchange

#### Scenario: The user walks away
- GIVEN a grant persisted for the browser
- WHEN the user closes the connection flow or steps back in it
- THEN the persisted grant is gone, and a late redirect redeems nothing

#### Scenario: An upgrade over a plaintext grant
- GIVEN the plaintext preferences an older version wrote for a grant
- WHEN the app starts
- THEN they are wiped

## REMOVED Requirements

None.
