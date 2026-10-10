---
cairn: change
id: gmail-first-sync-stall
status: active
created: 2026-10-10
---

# A Gmail first sync no longer stalls on a minute's wait

## Why

A fresh Google onboarding on the owner's phone (logcat of 2026-10-10, 20:20) sat in the strip's indeterminate phase for 94 s: the first mail pass logged nothing from 20:20:38 to 20:22:11, then two pages landed together, SENT (69) and INBOX (235), each after 92 s on the network, *12 mailboxes on 2 sessions in 94021 ms*. A body round later took 62 s for 7 messages. No throttling line showed, because the bridge installed no logger: every Rust `log` record was dropped.

A probe against the test account (google@pimalaya.org, read-only, 2026-10-10) settled the cause:

- two metadata batches of 50 sent side by side, as the two Gmail workers do, drew 17 to 33 inner answers of 50 each `429 Too many concurrent requests for user` (`rateLimitExceeded`, `RESOURCE_EXHAUSTED`); one batch of 50 alone still drew 3 to 10;
- one batch of 25 at a time, paced at 200 units a second, read 300 envelopes in 7.5 s with one 429 in all.

The bridge re-read each refused inner answer alone, and classed every Google 429 as a per-minute quota: the worker slept to the next wall-clock minute and exhausted the process's minute budget, so every other worker waited a minute too, twice in a row here. The pacing also counted a batch as one request of a 40-a-second rate though it bills 250 units, five times Gmail's 250 units a second when two go at once. The 62 s body round is the same minute budget, spent by the listing.

Why 235 in INBOX when a first chunk is 50: the test account's mail is imported, its `Date` headers scattered over the year while Gmail received it all at once, so the oldest `Date` among the 50 newest received is older than the account's bound, and the first chunk is the whole bound (the log's `since 2026-01-01`). The listing then reads the account's every envelope received since. That is the account's data, not a fault; it is what makes the first sync's cost a matter of pacing.

Three device findings ride along: the strip opened on *Synchronisation des messages* while nothing was counted; its indeterminate bar was thinner than the determinate one; and every phone sync of an address book leaked a SQLite connection pool (`A SQLiteConnection object for database pimdir.db was leaked`, `PimdirStorage.pending` from `SyncService`). Later in the same session a fill step ran 47 s with no log, and opening a message queued behind it on the one io executor.

## What

- **The bridge logs.** A `log` logger installed at `JNI_OnLoad`, to logcat under the app's tag: debug for the bridge, info for the libraries.
- **Throttling.** A 429, a 503 or Google's rate-limit 403 backs off a second or two with jitter, or until the `Retry-After` or the `Retry after <instant>` Gmail's message names; only an answer naming a per-minute quota waits for the next minute and holds the account's pacing. Every wait logged at debug with its status and reason.
- **Gmail pacing per account,** by quota units: 200 a second (Gmail allows 250), a batch costing all its calls, under a minute ceiling of 4,800 of the project's 6,000; metadata batches of 25, one of an account at a time; the reader's fetch skips the queue and draws on the minute's remaining fifth.
- **A round stops past its floor.** A Gmail round reads its page a batch at a time in Gmail's order of reception and ends once a whole batch was received before its floor, rather than reading the two days of margin its `after:` takes.
- **The fill steps smaller on Gmail,** 100 messages rather than 500, and **opening a message has its own executor,** its store writes under the store's one writer.
- **One store per process**, the sync adapters, the job and the queue sharing the activity's `PimdirDb` and `CardStore`.
- **The strip** says *Starting the sync* while its bar runs indeterminate, and the indeterminate bar is the determinate one's track at its height.

## Expected

A first Gmail chunk of an ordinary account (50 messages, the inbox's own 50, a batch past the floor: 100 to 150 reads) lands in 3 to 5 s; the imported test account's whole-bound first chunk (about 600 reads) in about 15 s, against 94 s.
