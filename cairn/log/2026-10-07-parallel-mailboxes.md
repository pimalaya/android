---
cairn: log
change: parallel-mailboxes
landed: 2026-10-07
---

# An account's mailboxes sync side by side

Capabilities moved: mail (added: An account's mailboxes sync side by side; modified: A pass takes the inbox first, Older mail fills in behind, Each page's time is logged, and the introduction).

The owner's device spent 6.8 s of a 12-mailbox first sync on Graph's network, mailbox after mailbox, against about 0.3 s in the bridge and the store. An account's mailboxes now run on a pool of sessions: Graph and JMAP 4, IMAP 3 (a connection and a login each), Gmail 2 (its process-wide pacing unchanged).

**Pool.** `MailPool` opens a session per worker slot as the worker needs it, the pass's own session (the outbox drain and the roster) being the first; the workers claim the mailboxes in the pass's order, the inbox first; a pool runs one run at a time, so a session handle is never on two threads at once. A mailbox's failure leaves the others running and is answered per mailbox, the pass reporting the first; the sync stamp is set only when none failed. Each run logs one line, `mail pass <account>: N mailboxes on K sessions in W ms, remote summed R ms`, the floor reads counted as network beside the listing.

**Store.** `PimdirEngine.STORE` is held while any driver answers a load, a lookup or a write, so the store keeps one writer and only the network overlaps.

**Where.** Every mail pass and the first-sync dialog, whose detail line counts the mailboxes landed of the account's total; the scroll widening, every limiting mailbox at once and every account at once (`MailPool.together`); the background fill, a step widening the next mailboxes of the fill's order, as many of an account as its pool runs (`MailFill.batch`), on pools kept between steps. Calendars and contacts are unchanged.

**Tests.** `MailPoolTest` over a fake with 120 ms a request and the real engine and store: 12 mailboxes on 4 sessions in about 0.9 s against 2.9 s one after another, the network summed over every mailbox; one session begins them in the pass's order and two begin the inbox among the first and the junk last; no session used by two workers at once; every write under the lock and none overlapping; one refused mailbox leaving the others stored; the caller's session serving the first worker and left open; a fill step's batch.
