---
cairn: change
id: parallel-mailboxes
status: landed
created: 2026-10-07
---

# An account's mailboxes sync side by side

## Why

Measured on the owner's device, the first-sync dialog of a Microsoft 365 account (12 mailboxes, 326 messages): 6.8 s on the network, mailbox after mailbox (60 to 400 ms each, 3.7 s for *Deleted Items*), against 84 ms of JSON, 35 ms in the engine and 185 ms in the store. The wait is the network's, one mailbox at a time, and nothing in a mailbox's pass depends on another's.

## What

Decided by Clément: a pool of sessions per account, its mailboxes run concurrently in the pass's order.

1. **Pool sizes.** Graph 4 and JMAP 4 (HTTP requests, nothing held); IMAP 3, each worker its own connection and login; Gmail 2, its reads still paced near 40 a second across the whole process by the one bucket the bridge keeps.
2. **One session per worker.** A session handle is a pointer into the bridge used from one thread at a time: each worker opens its own as it needs one (the pass's own session, which drained the outbox and read the roster, is the first worker's), and a pool runs one run at a time. Sessions read the roster on demand (mailbox-roster-on-demand), so a worker's fresh session addresses any mailbox.
3. **Order.** The workers take the mailboxes in the pass's order (`MailEngine.ordered`: inbox, sent, drafts, others by name, junk and trash last), so the inbox is begun first.
4. **One store writer.** Every storage yield (load, lookup, write) of every driver is answered holding one lock, so the store sees one writer and a page's write lands whole; only the network overlaps.
5. **Where it runs.** The first-sync dialog, every ordinary pass, the scroll widening (each limiting mailbox at once, every account at once) and the background fill (a step widens the next mailboxes of the fill's order, as many of an account as its pool runs). The dialog closes once every mailbox's first chunk has landed, its detail line counting the mailboxes landed of the account's total.
6. **Failures.** A mailbox failing leaves the others running; the pass reports the first failure as before, and the account's sync stamp is set only when none failed.
7. **Logs.** The per-page timing lines stay; each run adds one line with its wall time against the network time summed over its mailboxes (the floor reads included), so the gain shows on a device.

## Out of scope

- Calendars and contacts: unchanged.
- Accounts in parallel within an ordinary pass: accounts stay one after another there (the scroll and the fill already run every account at once).
- A Gmail envelope read by two workers is read twice, the cache being per session; the pacing bucket bounds the cost.
