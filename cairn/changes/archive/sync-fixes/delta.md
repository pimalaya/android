---
cairn: delta
change: sync-fixes
---

## ADDED Requirements

### Requirement: A message's date is its Date header
A Graph message's date SHALL be its `sentDateTime`, which is Graph's name for the `Date` header that pimdir STORAGE Annex A.1 stores and sorts mail by, and a Graph mailbox's newest messages SHALL be the newest by it. A message with no date SHALL be stored with none, never with its reception time in its place.

#### Scenario: A message received late
- GIVEN a Graph message sent at 08:00 and received at 08:05
- WHEN the mailbox is synced
- THEN the message's date is 08:00

### Requirement: An HTTP sync rides out throttling
Every HTTP backend (Graph, Gmail, Google Calendar and People, CalDAV, CardDAV, JMAP) SHALL send a request again when the server answers 429, 503, or 403 with Google's `rateLimitExceeded` or `userRateLimitExceeded`, after the `Retry-After` the server named, or else after an exponential back-off with jitter. The waiting SHALL be bounded per request and per native call, a `Retry-After` past the bound SHALL NOT be waited, and past the bound the throttled answer SHALL fail the request as any error does. An answer whose end cannot be told SHALL NOT be retried.

#### Scenario: Graph asks for a pause
- GIVEN a Graph request answered 429 with `Retry-After: 2`
- WHEN the bridge reads the answer
- THEN it waits two seconds and sends the request again, and the sync goes on with the answer to that

#### Scenario: A server that keeps throttling
- GIVEN a server answering 503 to every request
- WHEN a sync pass reaches it
- THEN the request fails with the 503 within the bound, and the pass reports it as it reports any error

### Requirement: Gmail is paced below its quota
Gmail API requests SHALL be paced near 40 a second across every worker of the process, below the 50 metadata reads a second Gmail's per-user quota allows, rather than sent until Gmail answers 429.

#### Scenario: A first round over a large label
- GIVEN several workers reading Gmail envelopes at once
- WHEN they run
- THEN their requests together go out at about 40 a second

## MODIFIED Requirements

## REMOVED Requirements
