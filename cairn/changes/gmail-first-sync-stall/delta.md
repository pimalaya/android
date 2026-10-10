---
cairn: delta
change: gmail-first-sync-stall
---

Folds into `spec/mail.md`, the strip and the store into `spec/offline-store.md`.

## ADDED Requirements

### Requirement: Opening a message waits on no background work
Opening a message SHALL NOT queue behind a step of the background fill or of the body download: its read and its fetch SHALL run apart from them, and what it writes to the store SHALL be written under the store's one writer. A Gmail fetch for the reader SHALL go out ahead of the background requests the account's pacing holds (Gmail is paced below its quota).

#### Scenario: Opening during the fill
- GIVEN the background fill widening a Gmail account
- WHEN a message not on the phone is opened
- THEN its fetch goes out at once, and the reader renders it without waiting for the fill's step to end

### Requirement: The bridge logs to logcat
The Rust bridge SHALL log to logcat under the app's tag: debug and above for its own records, info and above for the libraries under it. Every Gmail request SHALL be logged at debug with its call, its quota units, its pacing wait and the time its answer took, and every widening of a mailbox with its floor and the time it took.

#### Scenario: A throttled request
- GIVEN a Gmail request answered 429
- WHEN the bridge waits to send it again
- THEN logcat names the status, the reason and the wait

### Requirement: The process holds one store
Every caller in the process, the activity, the sync adapters, the background job, the phone queue and the time-zone receiver, SHALL share one store (`PimdirDb`) and one card store, so no call leaves a connection pool open behind it.

#### Scenario: A phone sync
- GIVEN an address book mirrored on the phone
- WHEN the system runs its sync adapter
- THEN the adapter uses the process's store, and no SQLite connection is reported leaked

## MODIFIED Requirements

### Requirement: An HTTP sync rides out throttling
Every HTTP backend (Graph, Gmail, Google Calendar and People, CalDAV, CardDAV, JMAP) SHALL send a request again when the server answers 429, 503, or 403 with Google's `rateLimitExceeded` or `userRateLimitExceeded`, after the `Retry-After` the server named or the instant Google's message names (`Retry after …`), or else after an exponential back-off with jitter from about a second. Only an answer naming a per-minute quota (`Quota exceeded for quota metric … per minute per user`) SHALL wait for the next minute, and on Gmail SHALL hold the account's pacing a minute; a rate or concurrency refusal (`Too many concurrent requests for user`, `User-rate limit exceeded`) SHALL NOT. An answer naming a daily quota SHALL NOT be retried. The waiting SHALL be bounded per request and per native call, a `Retry-After` past the bound SHALL NOT be waited, and past the bound the throttled answer SHALL fail the request as any error does. An answer whose end cannot be told SHALL NOT be retried. Every wait SHALL be logged at debug with the answer's status and reason.

#### Scenario: Graph asks for a pause
- GIVEN a Graph request answered 429 with `Retry-After: 2`
- WHEN the bridge reads the answer
- THEN it waits two seconds and sends the request again, and the sync goes on with the answer to that

#### Scenario: Gmail's concurrency limit
- GIVEN a Gmail metadata read answered 429 `Too many concurrent requests for user`
- WHEN the bridge reads the answer
- THEN it sends the read again within a second or two, never waiting for the next minute

#### Scenario: A server that keeps throttling
- GIVEN a server answering 503 to every request
- WHEN a sync pass reaches it
- THEN the request fails with the 503 within the bound, and the pass reports it as it reports any error

### Requirement: Gmail is paced below its quota
Gmail API requests SHALL be paced per account, every worker, the body step and the reader sharing the account's pacing: near 200 quota units a second, below the 250 Gmail's per-user rate allows, each request costing its documented units and a batch the units of every call it carries; and within a per-minute ceiling of four fifths of the project's 6,000 units per user. Metadata SHALL be read 25 to a batch, one batch of an account at a time: the calls of a batch run side by side at Gmail, and 50 of them draw its per-user concurrency 429s. A request someone waits on, a message being opened, SHALL take no slot behind the background requests, which wait for it, and SHALL draw on the fifth of the minute's quota the ceiling leaves. Every pacing wait SHALL be logged at debug.

#### Scenario: A first round over a large label
- GIVEN two workers reading Gmail envelopes at once
- WHEN they run
- THEN their batches of 25 go out one at a time, about 40 reads a second together

### Requirement: A mailbox is stored whole
A mail round SHALL list every message of a mailbox within its scope (its floor, within the account's bound), newest first in the source's own recency order, a page at a time (500 UIDs per IMAP `UID FETCH`, 1,000 per Graph `/messages` page, 500 ids per Gmail `messages.list`, 500 per JMAP `Email/query` capped by the server's `maxObjectsInGet`), each page landing in one write. Every message a page lists SHALL arrive named by the summary and sort key of pimdir STORAGE Annex A read in the listing itself, with no body: IMAP from `FLAGS`, `RFC822.SIZE` and the header fields Annex A reads, `Content-Type` among them and no `BODYSTRUCTURE`; Graph from the summary `$select`, or for a delta's member the store does not bind, from that `$select` read by id; Gmail from its metadata read; JMAP from `Email/get`'s summary properties. A Gmail round SHALL read its page's metadata a batch at a time in Gmail's order of reception, and SHALL end once a whole batch was received before its floor: the two days of margin its `after:` takes below the floor are mail dated below it but for a sender's clock running ahead by more than a batch of mail. An interrupted round SHALL resume from the cursor its last landed page left, and a cursor the source refuses SHALL restart the round. A round's last page SHALL retire only what it found absent within its scope; mail outside it SHALL never be deleted by a sync. The chunks and the fill (A mailbox is listed a chunk at a time, Older mail fills in behind) SHALL bring a mailbox whole within the account's bound.

### Requirement: Older mail fills in behind
After a mail tab's first sync, and on every return to the app or pass after it, every mailbox SHALL widen 500 messages at a time toward its account's bound (100 on Gmail, whose reads are paced by quota units, so a step holds nothing else up for long), with no dialog, the inbox and the sent mail first, a mailbox never listed before one that only lacks older mail, and among them the one holding the most recent floor; a step SHALL widen the next mailboxes in that order side by side, as many of an account as its pool runs. The fill SHALL run only while the app is in the foreground, on a network that is not metered, and while no other sync runs; it SHALL stop on an error, and SHALL resume from the floors the store covers.

### Requirement: A sync says what it is working on, in every domain
(In `spec/offline-store.md`.) The last sentence of the bar's paragraph becomes: The bar SHALL run indeterminate only before anything is counted, its line then saying the sync is starting (*Starting the sync*) rather than naming a domain, and SHALL keep the determinate bar's track, height and margins. The line paragraph's last sentence becomes: The strip's line SHALL never be empty: it SHALL open saying the sync is starting, and SHALL follow the pass to the next account and domain once something is counted. The scenario *The first frame* reads: THEN its line says the sync is starting, over an indeterminate bar the size of the counting one.

## REMOVED Requirements
