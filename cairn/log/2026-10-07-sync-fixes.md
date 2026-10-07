---
cairn: log
change: sync-fixes
landed: 2026-10-07
---

# Graph's date is the Date header, and HTTP syncs ride out throttling

Capabilities moved: mail (added: a message's date is its Date header; an HTTP sync rides out throttling; Gmail is paced below its quota).

**Graph date.** `MESSAGE_SELECT` asks for `sentDateTime` instead of `receivedDateTime`, the listing orders by `sentDateTime desc`, and `graph_message` takes the date from it, empty when absent, which is what Annex A.1's `NULL` crosses the wire as.

**Throttling.** `client/throttle.rs` puts `http_write` and `http_read` under every HTTP runner (`run`, `run_redirect`, `run_sync_collection`, `run_msgraph`, `run_msgraph_delta`, `run_google`, `run_gmail`, `run_gcal`, `run_jmap` and the JMAP download and changes runners) in place of the bare transport calls. The client records the bytes of the request in flight; the first read of its answer reads the head, and for a 403, 429 or 503 the body to its end by `Content-Length` or chunked framing. A throttled answer (429, 503, or a 403 naming Google's `rateLimitExceeded` or `userRateLimitExceeded`) is then waited on and the same bytes written again: the `Retry-After` in seconds or as an HTTP date, else 1 s doubling to 16 s scaled by a jitter in 0.5 to 1. At most 4 retries a request and 30 s of waiting a native call (`WAIT_BUDGET`); a `Retry-After` longer than what is left is not waited. Past that, or for an answer whose end cannot be told, the bytes read pass to the coroutine untouched and it fails as before. Discovery, IMAP and SMTP keep the bare transport.

**Gmail pacing.** `run_gmail` takes a slot from a process-wide pace (`GMAIL_REQUESTS_PER_SECOND`, 40, a cell rate with a burst of 10) before each request.

Not done here: reporting `throttled { source, until }` to the app, Graph's `$batch` sub-request 429s, and the Gmail and JMAP dates, still the reception time.
