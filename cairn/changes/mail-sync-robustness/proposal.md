---
cairn: change
id: mail-sync-robustness
status: active
created: 2026-10-11
---

# Mail sync robustness: dead connections, failed bodies, the gaps of Load more

## Why

On Microsoft 365, some messages inside the frontier stayed dimmed and never downloaded. The cause is verified: Graph closes an idle HTTP/1.1 keep-alive connection after 115 to 130 s, and nothing on the phone noticed. `Transport` kept a socket per origin for as long as it was held and never dropped one: a read at EOF answered an empty chunk, an `IOException` left the entry in place, and the bridge never told it to drop anything. `PimalayaClient.on()` reopened a session only when the JNI call threw, which it never does (the bridge answers error JSON), so a session on a dead socket failed every verb after. With a 30 s read timeout, an answer that arrived late stayed on the socket and could be read as the next request's: a body stored under the wrong message.

Behind that, a body that failed was dropped silently: `MailEngine.fetchBodies` logged it, `MailBodies.step` took the row off the plan whether it landed or not, nothing retried it in the run, and a token expiring mid-run cleared the account's plan. mail-load-more also left three gaps (its log's *Left open*): junk and trash are never listed on demand (B.13 rule 7), a step over one mailbox moves the whole account's frontier, and arrivals are tracked in memory only, so one dated below the frontier left over a restart stays a summary.

## What

### Connections

- `Transport` drops an origin's connection on any `IOException` (a read timeout included) and at EOF, and counts what it lost. Three upcalls the bridge uses: `begin(url)` before a new HTTP request, dropping the connection when the last answer on it was not read to its end, and answering whether the connection is reused; `settle(url)` once an answer was read to its end; `reset(url)` to drop it outright.
- The bridge calls `reset` on any transport error (`Client::read`, `write`), so every backend is covered, IMAP and SMTP included. throttle.rs follows each HTTP answer's framing (`Content-Length`, chunked) to its end and settles it then; an answer with no framing, one cut short, one carrying more than its framing, or one saying `Connection: close` is never settled, so its connection is dropped before the next request: a late answer can no longer be read as the next one's.
- A reused connection that ends or fails before the first byte of an answer to an idempotent request (GET, HEAD, OPTIONS, PROPFIND, REPORT, PUT, DELETE) is reset and the recorded request sent again once (RFC 9112 section 9.3.1).
- `PimalayaClient.on()` reopens the session and runs the verb once more when the reply is an error and the transport lost a connection since a verb last answered (in that call, or in a copy or an append run outside it): a dead IMAP socket re-greets and re-authenticates rather than failing every later call, and a refusal from a live server is no longer run twice.

### Failed bodies

- `MailEngine.download` answers the rows whose body did not land (asked of the server, not handed over). `MailBodies.step` puts them back at the end of the account's plan, at most 3 attempts a run; the host closes the account's sessions after a failure, so a retry runs on a reopened session and an expired token is renewed on the way. A row failing every attempt is marked failed for the process and its row says *Download failed* rather than a silent dim. A download failing as a whole (an expired token, no session) puts its rows back too; an account failing twice in a row as a whole leaves the run.
- A step's message whose body did not land is owed like an arrival, so it downloads whatever its date.

### Gaps of mail-load-more

- **Owed bodies persist** (`MailOwed`, beside the account in shared preferences, keyed by collection and link id): arrivals and a step's failed bodies survive a restart and download on the next run; one in a mailbox that downloads nothing (junk, trash) is dropped.
- **A mailbox stepped alone keeps its own frontier** (`MailWindow`, by collection): a step over one mailbox moves that mailbox's frontier, never the account's, and all mail once it is exhausted. A mailbox's effective frontier is the older of its own and its account's; the list's floor, the body planner, the row's state, the footer and `WidenStep` read it.
- **Junk and trash list when opened**: the mail list showing one account's junk or trash alone lists it once per opening on Wi-Fi, a Gmail one through a step over its label (`labelIds` SPAM or TRASH, `includeSpamTrash`, which the bridge already sends for those labels), any other through a pass over that mailbox.

## Edits outside the generic layer

- rust/src/client.rs (`Client::read`, `write`, the new `begin`, `settle`, `reset` upcalls) and throttle.rs: shared by every backend.
- `WidenStep`: one line, the frontier it reads is the mailbox's when it steps one alone.

## Out of scope

- A POST sent again after a dead connection (Gmail and Graph batches, JMAP calls): not idempotent by method; the session reopening covers them at the verb level.
- Persisting the failed mark: it lasts the process, every run trying again.
