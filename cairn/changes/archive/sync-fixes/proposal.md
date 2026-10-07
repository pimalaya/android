---
cairn: change
id: sync-fixes
status: landed
created: 2026-10-07
---

# Graph's date is the Date header, and HTTP syncs ride out throttling

## Why

Three fixes the joint plan for scoped mail sync (pimdir, `scoped-mail-sync`, section 8) lists as needing no spec of their own, taken first in the bridge:

- A Graph message's date was its `receivedDateTime`, under a column pimdir STORAGE Annex A.1 defines as the `Date` header, and it is also the sort key, so a Graph list ordered by reception where every other backend orders by the header.
- No HTTP backend followed a throttling answer: a 429, a 503 or Google's rate-limit 403 failed the request, and with it the pass, where waiting a few seconds would have served it.
- Gmail grants 250 quota units per user per second and a metadata read costs 5, so about 50 reads a second; a pass reading envelopes one at a time from several workers can reach it, and then only learns from the 429s.

## What

- Graph's listing selects and orders by `sentDateTime`, and a message's date is that; one with none has no date (Annex A.1: `NULL`), rather than the reception time standing in.
- Every HTTP runner (Graph, Gmail, Google Calendar and People, CalDAV, CardDAV, JMAP) writes and reads through one throttling layer in the bridge (`client/throttle.rs`). A 429, a 503, or a 403 naming `rateLimitExceeded` or `userRateLimitExceeded` is read to its end and the same request sent again after the `Retry-After` the server named, or else a back-off from 1 s doubling to 16 s, jittered. Bounded: 4 retries a request, 30 s of waiting a native call, a `Retry-After` longer than what is left not waited at all; past that, the throttled answer reaches the coroutine and fails as it did before. An answer whose end cannot be told (no length nor chunking, or `Connection: close`) is never retried.
- Gmail requests are paced near 40 a second, process-wide so every worker shares it, with a burst of 10 after a pause.

Out of scope here, per the plan: pages and scopes, `Prefer: odata.maxpagesize`, reporting `throttled { source, until }` to the app, and the Gmail and JMAP dates, which are still the reception time.
