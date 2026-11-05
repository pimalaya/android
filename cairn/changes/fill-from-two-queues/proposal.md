---
cairn: change
id: fill-from-two-queues
status: active
created: 2026-11-05
---

# The fill runs from two queues

## Why

A Graph account connected on the OnePlus A5000 on 2026-11-05, with the debug log (the Microsoft test account, ten folders ticked):

- **The first sync took 14.2 s** from the sign-in (08:10:49.6) to "50 brought" (08:11:03.8). 9.3 s went before its first page, in the step's walk of the account by date (A Graph step lists the account by date); the ten folders were then listed one after another on the step's one session (3.1 s), and their bodies folder by folder (1.5 s). The other three sessions of the pool sat idle.
- **The fill waits for its slowest mailbox at every step.** A fill step widens as many mailboxes as the pool runs and the next step starts once all are done (`MailFill.batch`, `SyncCoordinator.fillStep`). In the step begun at 08:11:15, Factures, Drafts and Conversation History were done by 08:11:19.7 and Inbox's 500 held the next step until 08:11:23.5; the last two steps widened Archive alone, on one session of four.
- **Bodies and listing exclude each other.** A fill step lists nothing while the body step has bodies left, and both run on the same pool, one after the other.
- **A page of 500 is the network's.** Graph answered a page of 500 envelopes in 6.4 to 6.5 s; the store wrote it in 1.5 to 1.6 s since io-pimdir's a-page-lands-in-few-statements (the list binding fixed the same day).

## What

1. **A listing queue per account.** The mailboxes the filter ticks wait in one queue: every mailbox never listed first (the inbox, then the sent mail, then the others), so all first chunks land before any fill chunk, then the fill's order for the rest (`MailFill.next`: the inbox and the sent mail first, then the most recent floor). Today's order would put the inbox's first 500 ahead of another mailbox's first chunk, holding the account's 25 bodies (item 3) about 6.5 s on Graph. A listing job takes the head whenever it is free: a mailbox never listed gets its first chunk, any other one fill chunk below its floor, and it goes back in the queue once its chunk is written, while it has older mail within its bound. No step waits for another mailbox.
2. **A first chunk of 25.** A mailbox's first pass lists its 25 newest messages, not 50: the inbox shows after one short request, then every chunk after is the fill's 500.
3. **The account's 25 newest, read whole.** Once every ticked mailbox's first chunk is written, the account's frontier is the `Date` of the 25th newest of them all, ties included, as the IMAP first step takes its 50th today. That is exact: any of the account's 25 newest messages is among its own mailbox's 25 newest, so the first chunks hold them all. The body step's rule (every listed message dated on or after the frontier) then owes those 25 bodies and no others, whatever the number of mailboxes. A mailbox whose first chunk fails is left out of the frontier; its messages above it, listed later, are owed then by the same rule. The bodies wait for the first chunks: about 1 to 1.5 s on three listing jobs for ten mailboxes (their first pages took 120 to 800 ms each on 2026-11-05). Starting from the inbox's chunk alone would guess the 25 and move the frontier later when another mailbox lands, which a frontier never does (An account's frontier is where its steps reached), and download bodies that end below it.
4. **A body queue per account, on its own session.** A body job downloads what the body step owes, newest first, alongside the listing: the first sync's 25, then a window widened, a mailbox kept whole, arrivals and owed bodies, as today. Opening a message still jumps it (Opening a message waits on no background work). The rule that a fill step lists nothing while bodies are left goes.
5. **Load more brings the next 25.** A step brings 25 messages whole, not 50, on every backend. On IMAP and Graph a step reads the store: it widens the mailbox holding the most recent coverage floor until 25 stored messages lie between it and the frontier, which the fill has usually listed already so it lists nothing, moves the frontier to the 25th of them, and reads them whole, as the IMAP step does today with 50. The Graph step's walk of the account by date goes: the fill lists the same envelopes per folder. Gmail and JMAP keep their own steps, at 25. The step size moved to 25 on every backend with this change; Graph's move to the store-side step is a follow-up.
6. **The pool splits once the first chunks are in.** The first sync lists every ticked mailbox's first chunk on the account's whole pool (four on Graph, three on IMAP), each written as it lands so the list shows it. Then the fill's listing jobs hold every session but one, which the body step holds: three and one on Graph, which stays within Exchange Online's four concurrent requests per mailbox, two and one on IMAP. Once the listing is done, the body step takes the account's whole pool again, so a mailbox kept whole downloads as fast as before. No backend runs a pool of one.
7. **The first sync ends once the first chunks are in.** On the mail tab's first sync of an IMAP or Graph account, every ticked mailbox lists its first chunk side by side and the frontier is set (item 3); the dock's pass ends then, and the 25 bodies and the fill's listing start behind it side by side, under the fill's rules (foreground, Wi-Fi, no other sync, stop on an error, resume from the floors reached), the dock's second line naming what each does. No account-wide step runs for it.
8. **Writes stay one writer.** Every job's loads, lookups and writes go through the store's lock as today; a listing job waits for its own write before taking the next mailbox (see the open points).
9. **Unchanged:** Gmail and JMAP are not filled and their first sync is their step, the account's bound, a mailbox kept whole, the Wi-Fi and foreground rules, the merged list's floor (the most recent of the shown mailboxes' frontiers and coverage floors, so envelopes listed below the frontier show as Load more passes them).

## The phases the user sees (2026-11-05)

The third device run (10:04) asked for the dock to follow the phases, and for one algorithm on every backend:

1. **Envelopes:** every ticked mailbox's first 25, side by side, each written as it lands; the dock's bar counts mailboxes landed and written (25 x N), and at its end they are all in the store and on screen.
2. **Bodies:** the frontier set at the account's 25th newest, what lies below hides, and the 25 bodies download within the same pass on one session, the bar indeterminate (`SyncPasses.Progress.uncounted`), their rows dimmed until each lands.
3. **The rest of the envelopes:** the fill lists them by 500, silently: no dock, no line.
4. **Load more:** a pass whose bar runs indeterminate while its 25 bodies download, the 25 rows shown dimmed first (`MailState.moved`).

The same run measured the bodies on all four Graph sessions at 7.6 s, throttled (`graph batch: throttled`), against 2.5 s on one: a Graph `$batch` already runs its requests side by side, so Graph's bodies keep to one session.

## Per backend

The algorithm is the guideline; each backend maps it within its own quotas.

- **IMAP:** as above: a first chunk is a floor (`UID SORT` or a sample) and a round, bodies stream on one connection, the fill widens by 500.
- **Graph:** as above: a first chunk is `$top` 25 by `sentDateTime`, bodies are one `$batch` of 20 on one session, the fill lists by band; four concurrent requests a mailbox in all.
- **JMAP:** as IMAP and Graph since 2026-11-05: the bridge already lists a mailbox by chunks (a floor by `Email/query` sorted `receivedAt`, then rounds of `Email/get` summary properties by band), so JMAP steps from the store (`MailStepKind::Store`) and is filled; its link ids still span mailboxes, so a message filed twice reads one body. The account-wide anchored step (2026-10-11) was there to spare Load more a widening per mailbox, which the fill now does ahead of it.
- **Gmail (kept as it is):** a label lists ids only (`messages.list`), and each envelope costs a `messages.get`, documented at 20 units of 6,000 a user a minute (rust/src/mail/gmail.rs `quota`; Google bills 5 of 15,000 today). Envelopes then bodies is two reads a message where a raw read is one; a fill of 500 envelopes is 10,000 documented units, nearly two minutes of the user's budget, 20,000 messages more than an hour of it, every sync and Load more queuing behind. Gmail keeps its account-wide step of 25 read whole, and is not filled: its search beyond the phone is the server's.

## Open points

- **A session waits for its own write.** The engine's writes are answered synchronously (`OfflineDriver.serve`, `Stager`), so a listing session is off the network while its page is written: about a fifth of its time at 6.5 s fetched and 1.6 s written. A second queue for writes would free nothing as long as the session is held through the round. Two ways out, measured after the rest: a request permit in the bridge for the HTTP backends instead of one session per job (Graph limits concurrent requests, not sessions), so a job writing holds none and one more job lists meanwhile; or the engine answering a pull's write before it lands, which needs its loads to wait for that mailbox's pending write. IMAP holds a connection selected on its mailbox either way.
- **The Graph step's walk goes** (item 5). A view filtered to one folder, `InefficientFilter` and 504 fallbacks, and the busy-second rule go with it; the store-side step handles a one-folder view as the IMAP step does. If a Graph account's fill has not listed below the frontier yet (a narrower bound than the step reaches, a fill stopped on an error), the step widens folders on its own, chunk by chunk, before it reads the bodies.
- **Engine time on a first page:** 1,531 ms for a page of 20 at 08:10:59.2, 4,617 ms at 07:52:21; unexplained, outside this change.
