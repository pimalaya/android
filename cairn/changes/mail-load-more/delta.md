---
cairn: delta
change: mail-load-more
---

Folds into `spec/mail.md`. The intro changes too. Its second paragraph's first sentence becomes:

> A message rises off that rung by being opened, or by a step of its account (about 50 messages brought whole, older than what it holds) and the body step behind the list, on Wi-Fi only: what was fetched is filed as the item's object, as the bytes the server sent, so the item reaches full and every read after it, offline included, is a read of the store.

Its third paragraph's first sentence becomes:

> Every mailbox but Gmail's runs io-pimdir's sync, on a session of the pass's pool, from its floor: its first pass lists its 50 newest messages, the floor being the oldest `Date` among them, and a background fill widens it a chunk of messages at a time toward the account's bound, all of its mail or the last N months on the `Date` header.

Its fourth paragraph's Gmail sentence becomes:

> A Gmail mailbox is a label filing mail, its name already a path; the account is stepped through once, its messages read whole 10 to a batch under the account's pacing and placed in every label they carry, and every pass after replays the history from the `historyId` taken before the first step, reading again only the messages that moved.

## ADDED Requirements

### Requirement: Mail syncs on Wi-Fi only
A mail pass SHALL list nothing, and no step, fill step or body download SHALL run, on a metered network; the outbox SHALL still go out there. The mail list's strip SHALL then say the sync waits for Wi-Fi (*Waiting for Wi-Fi*), and what was asked SHALL run once an unmetered network is back while the app is open. A background run on a metered network SHALL send the outbox and list nothing. Opening one message SHALL fetch it on any network.

#### Scenario: A first sync on mobile data
- GIVEN an account just connected, on mobile data
- WHEN the mail tab is opened
- THEN nothing is listed, the strip says it waits for Wi-Fi, and the first step runs once Wi-Fi is back

#### Scenario: Opening a message on mobile data
- GIVEN a message not on the phone, on mobile data
- WHEN it is opened
- THEN it is fetched and shown

### Requirement: An account's mail comes in steps
An account's mail SHALL come in steps: a step SHALL bring about 50 messages of the account whole (headers and body, plain `Full`), older than what the account holds, never counted by an offset, on the account's one session, its reads in small batches one after another. Each backend SHALL decide how a step finds them and batches them. The first sync SHALL be one step. A step SHALL leave out the junk and the trash. A view filtered to one mailbox of an account that has several SHALL step that mailbox alone. A step finding nothing older SHALL leave the account at all mail, unless it stepped one mailbox alone.

#### Scenario: A first sync
- GIVEN an account just connected, on Wi-Fi
- WHEN the mail tab's first sync runs
- THEN about 50 messages are listed and readable offline when it ends

#### Scenario: A small account
- GIVEN an account holding 30 messages
- WHEN its first step runs
- THEN all 30 are on the phone, and the list has nothing more to load

### Requirement: An account's frontier is where its steps reached
Each mail account SHALL keep a frontier, as app state beside the account: the `Date` of the oldest message its steps brought, leaving out a message dated more than a day before it was received (an import, a wrong clock), which is held and shown once the list's floor passes it; the oldest of them when every one is such. A step SHALL only move the frontier back; only the account's settings move it later (A window moved later frees the bodies below it). The merged list's floor SHALL be the most recent of the shown accounts' frontiers and of the shown mailboxes' coverage floors, a Gmail mailbox bringing no coverage floor.

#### Scenario: An imported message
- GIVEN a step bringing messages dated from 8 to 9 October and one dated March 2019 received on 9 October
- WHEN it ends
- THEN the frontier is 8 October, the March 2019 message held and not listed above it

### Requirement: The mail list ends on Load more
While a shown account that is on has more to load, the mail list SHALL end on a footer: a button, *Load more*, over a line saying about how many messages a step brings each account (*About 50 more per account*). A tap SHALL take one step of each such account, one after another under the sync strip, then read the list again and download the bodies left. Off Wi-Fi the line SHALL add that it waits for Wi-Fi and the button SHALL be disabled. Junk, trash and mailboxes kept whole SHALL take no part. The footer SHALL hide under a search, and SHALL stand in for an empty list's empty state.

#### Scenario: Two accounts
- GIVEN accounts A and B shown together, both with older mail
- WHEN *Load more* is tapped
- THEN A then B take a step, and the list reaches down to the more recent of their new frontiers

#### Scenario: Off Wi-Fi
- GIVEN a list on mobile data
- WHEN its end is reached
- THEN the footer says it waits for Wi-Fi, and its button does nothing

### Requirement: A Gmail step is a set difference
A Gmail step SHALL walk the account's `messages.list` (no `labelIds`, `includeSpamTrash=false`, 500 ids a page) from the top, newest received first, keeping the ids the store does not hold anywhere in the account until it holds about 50, and SHALL never filter on a date (`before:` on `internalDate` or `Date`). A view filtered to one label SHALL walk that label's listing (`labelIds`). The account's `historyId` SHALL be taken before the walk. The ids kept SHALL be read with `format=minimal` for their sizes, then with `format=raw` in batches of at most 10 messages and about 8 MB, one batch at a time, a message over about 5 MB read alone. Each message SHALL be read once and placed in every mailbox its `labelIds` name, a message in the trash or the spam in that one alone, its body filed once and linked into the others. The first step SHALL open every label's mailbox, the junk and the trash included, as a round closed on that `historyId`, so the next pass is a history replay; a later one SHALL add to them.

#### Scenario: Two labels
- GIVEN a Gmail message filed under INBOX and Clients, not on the phone
- WHEN a step brings it
- THEN it is read once, and both mailboxes hold it whole

#### Scenario: A gap
- GIVEN a Gmail account holding its 50 newest messages but one a failure left out
- WHEN the next step runs
- THEN the missing one is among the messages it brings

### Requirement: Bodies cross the bridge as files
A message read whole for the store SHALL cross the bridge as a file: `fetchMessageSources(handle, collection, ids, dir)` SHALL write one file per message into `dir` and answer each one's id, path and size (with its labels and reception on Gmail), a message gone or not handed over left out, an expired token failing the call. It SHALL dispatch per backend, a backend without batches of its own reading one message after another. The file SHALL be filed in the blob store under its hash and handed to the engine as a body already persisted. Opening one message SHALL keep its own path.

#### Scenario: A batch of bodies
- GIVEN ten Gmail messages to download
- WHEN the body step reads them
- THEN one batch request answers them, each written to its own file, and no body crosses as a string

### Requirement: Arrivals download whatever their date
A message a delta lists new to the store (IMAP CONDSTORE or QRESYNC, Gmail history, Graph delta, JMAP changes) SHALL download its body on Wi-Fi whatever its `Date`, a delayed message or one filed from older mail included, its row not saying *Not downloaded* meanwhile.

#### Scenario: A delayed message
- GIVEN an account whose frontier is 1 October
- WHEN a pass lists a new message dated 20 September
- THEN its body downloads with the others

## MODIFIED Requirements

### Requirement: An HTTP sync rides out throttling
Every HTTP backend (Graph, Gmail, Google Calendar and People, CalDAV, CardDAV, JMAP) SHALL send a request again when the server answers 429, 503, or 403 with Google's `rateLimitExceeded` or `userRateLimitExceeded`, after the `Retry-After` the server named or the instant Google's message names (`Retry after …`), or else after an exponential back-off with jitter from about a second. Only an answer naming a per-minute quota (`Quota exceeded for quota metric … per minute per user`) SHALL wait longer: until the next minute, except on Gmail, whose quota refills by the second, where it SHALL wait 2 s, then a second more each time up to 5 s, never to the next minute and without holding the account's pacing; a rate or concurrency refusal (`Too many concurrent requests for user`, `User-rate limit exceeded`) SHALL NOT. An answer naming a daily quota SHALL NOT be retried. The waiting SHALL be bounded per request and per native call, a `Retry-After` past the bound SHALL NOT be waited, and past the bound the throttled answer SHALL fail the request as any error does. An answer whose end cannot be told SHALL NOT be retried. Every wait SHALL be logged at debug with the answer's status and reason.

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

### Requirement: Gmail is paced below its quota
Gmail API requests SHALL be paced per account, every worker, the body step and the reader sharing the account's pacing, each request costing its documented units (a `messages.get` 20) and a batch the units of every call it carries: near 1,000 units a second with a burst of one batch, within a per-minute ceiling of four fifths of the documented 6,000 units per user, the units and rates kept in one place beside the 5 units against 15,000 a minute measured on 2026-10-10. Messages SHALL be read 10 to a batch, one batch of an account at a time: the calls of a batch run side by side at Gmail, whose per-user concurrency limit is what binds. Within a batch, a read refused for concurrency or left unanswered SHALL be put back into the next batch, sent after 1 to 2 s with jitter; one refused a per-minute quota SHALL be put back after 2 to 5 s, more each time; a daily quota SHALL stop the read. A request someone waits on, a message being opened, SHALL take no slot behind the background requests, which wait for it, and SHALL draw on the fifth of the minute's quota the ceiling leaves. Every pacing wait SHALL be logged at debug.

#### Scenario: A step's reads
- GIVEN a step reading 50 Gmail messages
- WHEN it runs
- THEN they go out in batches of 10, one at a time, and a read refused for concurrency rides the next batch

### Requirement: A mailbox is stored whole
A mail round SHALL list every message of a mailbox within its scope (its floor, within the account's bound), newest first in the source's own recency order, a page at a time (500 UIDs per IMAP `UID FETCH`, 1,000 per Graph `/messages` page, 500 ids per Gmail `messages.list`, 500 per JMAP `Email/query` capped by the server's `maxObjectsInGet`), each page landing in one write. Every message a page lists SHALL arrive named by the summary and sort key of pimdir STORAGE Annex A read in the listing itself, with no body: IMAP from `FLAGS`, `RFC822.SIZE` and the header fields Annex A reads, `Content-Type` among them and no `BODYSTRUCTURE`; Graph from the summary `$select`, or for a delta's member the store does not bind, from that `$select` read by id; JMAP from `Email/get`'s summary properties. A Gmail round, which a history Gmail no longer holds or a label new to the store opens, SHALL list the label's ids received since the oldest reception the account holds, named by their markers alone (the unread and starred labels' ids), the store keeping the summary it holds and reading by metadata only the members it holds in another mailbox or that arrived ahead of every message it holds, never one read per held message. An interrupted round SHALL resume from the cursor its last landed page left, and a cursor the source refuses SHALL restart the round. A round's last page SHALL retire only what it found absent within its scope; mail outside it SHALL never be deleted by a sync. The chunks and the fill (A mailbox is listed a chunk at a time, Older mail fills in behind) SHALL bring a mailbox whole within the account's bound, except on Gmail, whose steps decide what it holds.

#### Scenario: A first pass over a large mailbox
- GIVEN an IMAP mailbox of 100k messages and an empty store
- WHEN it is synced
- THEN its newest chunk is listed with its subject, sender and date before the first sync ends
- AND the rest is listed behind it, a chunk at a time

#### Scenario: An interrupted first pass
- GIVEN a round cut off after its first page
- WHEN the mailbox is synced again
- THEN it resumes below the last landed page rather than from the top

#### Scenario: A bounded account
- GIVEN an account bounded to the last 6 months
- WHEN it is synced and filled
- THEN older messages are neither fetched nor listed, and none already stored is deleted

#### Scenario: An expired Gmail history
- GIVEN a Gmail account whose history Gmail no longer holds
- WHEN a pass opens a round over its inbox
- THEN the inbox's ids are listed with their markers, and no message the store holds is read again

### Requirement: A mailbox is listed a chunk at a time
A mailbox's first pass SHALL list its newest messages alone: the scope's floor SHALL be the oldest `Date` among the 50 newest the source names (the last 50 UIDs `UID SEARCH` finds, Graph's `$top` ordered by `sentDateTime`, JMAP's `Email/query` by `receivedAt`), or no floor when it holds fewer, within the account's bound. A chunk SHALL be a number of messages, never a span of time, and the floor SHALL be all that is kept of it: the coverage of the round that listed it. A pass after the first SHALL list from that coverage, or from the round under way, so it is a delta, once a Graph mailbox's delta link is made (A Graph mailbox keeps one unfiltered delta link). Widening a mailbox SHALL take the next chunk below its floor, the oldest `Date` among the newest messages dated before it, and list only the band it lacks, on every backend: no checkpoint is bound to a scope. A Gmail mailbox SHALL be neither chunked nor widened: its account's steps bring what it holds.

#### Scenario: A first pass over a large mailbox
- GIVEN a mailbox of 3,000 messages never listed
- WHEN it is synced
- THEN its 50 newest are listed, and its floor is the oldest `Date` among them

#### Scenario: A small mailbox
- GIVEN a mailbox of 20 messages
- WHEN it is synced the first time
- THEN all of them are listed, and nothing is left below its floor

#### Scenario: Widening
- GIVEN a mailbox listed down to its 50th newest message
- WHEN it is widened
- THEN the next 50 below the floor are listed, the band alone

### Requirement: The merged list reaches down to its mailboxes' floor
The merged list SHALL show no message older than its floor (An account's frontier is where its steps reached), so no account's or mailbox's older mail is listed while another's of the same days is not on the phone yet; a search SHALL still cover every stored message. While a shown account has more to load, the list SHALL end on its footer (The mail list ends on Load more).

#### Scenario: Two mailboxes at different depths
- GIVEN an inbox listed down to Tuesday and a sent mailbox down to the week before, on an IMAP account whose frontier is earlier
- WHEN the list is scrolled to its end
- THEN it ends at Tuesday, on the footer

### Requirement: Older mail fills in behind
After a mail tab's first sync, and on every return to the app or pass after it, every mailbox but Gmail's SHALL widen 500 messages at a time toward its account's bound, with no dialog, the inbox and the sent mail first, a mailbox never listed before one that only lacks older mail, and among them the one holding the most recent floor; a step SHALL widen the next mailboxes in that order side by side, as many of an account as its pool runs. A step SHALL list nothing while the body step has bodies left that it may download now. The fill SHALL run only while the app is in the foreground, on Wi-Fi, and while no other sync runs; it SHALL stop on an error, and SHALL resume from the floors the store covers. A Gmail account SHALL NOT be filled: its search beyond what its steps brought is the server's.

#### Scenario: Leaving the app
- GIVEN a fill under way
- WHEN the app goes to the background
- THEN no further chunk is listed, and the next return resumes below the floors reached

#### Scenario: A metered network
- GIVEN a phone on mobile data
- WHEN the app is open
- THEN no chunk is listed until Wi-Fi is back

### Requirement: An account's mailboxes sync side by side
A mail pass and the background fill SHALL run an account's mailboxes concurrently on a pool of sessions, four for Graph and JMAP, three for IMAP, two for Gmail, each worker on a session of its own that no other worker uses while it runs, the workers taking the mailboxes in the pass's order. A step, the first sync's included, SHALL run on the account's one session. Every storage load, lookup and write SHALL be answered by one writer at a time, so only the network overlaps. A mailbox that fails SHALL leave the others running, the pass reporting the first failure.

#### Scenario: A pass over twelve mailboxes on Graph
- GIVEN a Graph account of twelve mailboxes
- WHEN a pass runs
- THEN four mailboxes are listed at once, the inbox begun first

#### Scenario: One mailbox refused
- GIVEN a pass over five mailboxes, one of which the server refuses
- WHEN the pass runs
- THEN the other four are stored, and the pass reports the refusal

#### Scenario: Two pages landing together
- GIVEN two mailboxes whose pages arrive at the same moment
- WHEN both are written
- THEN one write lands whole before the other begins

### Requirement: Bodies download behind the list
For each account that is on, the app SHALL raise to `Full`, through the engine's upgrade, every listed message dated on or after the account's frontier in every mailbox but the junk and the trash, every listed message of a mailbox kept whole, and every arrival (Arrivals download whatever their date), after each pass, step and fill step while the app is open: newest first, the mailboxes the mail filter shows before the account's others, the arrivals last, at most 24 bodies an account a step. A step's own messages SHALL be stored from the files it read, never read again. A message with no date SHALL be downloaded only under a frontier of all mail or in a mailbox kept whole, and a pending create never. Whether a message is taken SHALL be read again against the current frontier as its chunk downloads. A body the store already holds under the same link id SHALL be linked rather than fetched. The body SHALL be stored as the bytes the server sent, its base moved to it, its summary and sort key kept, its attachment mark restated from its parts. The step SHALL run only while the app is in the foreground, no other sync runs, and on Wi-Fi; it SHALL never be periodic work, and SHALL stop when the user starts a sync or leaves the app. A body the server fails to hand over SHALL be left below `Full` and tried by a later run; an account whose download fails as a whole (no session, an expired token) SHALL leave the run.

#### Scenario: Within the frontier only
- GIVEN an account whose frontier is 1 September, holding a header from yesterday and one from March
- WHEN the body step runs
- THEN yesterday's body is downloaded and stored, the March one stays a summary

#### Scenario: Junk and trash
- GIVEN a message from yesterday in the junk, its mailbox not kept whole
- WHEN the body step runs
- THEN its body is not downloaded

#### Scenario: A metered network
- GIVEN an account on mobile data
- WHEN a pass would end
- THEN no body is downloaded until Wi-Fi is back

#### Scenario: The same message under two labels
- GIVEN a Gmail message under two labels, its body downloaded in one
- WHEN the body step reaches the other
- THEN the body is linked from the store and not read again

### Requirement: The mail list shows its background download
While the background fill downloads older mail or bodies, the mail list SHALL show a download glyph beside its count, pulsing gently in opacity, and holding still when the system removes animations; a long press SHALL name it (*Loading older mail*). It SHALL show only once a step downloaded something, and SHALL go when the fill ends or pauses (the app left, no Wi-Fi).

#### Scenario: After the first mail sync
- GIVEN a first mail sync that landed the newest messages of an IMAP account
- WHEN the fill widens its mailboxes in the background
- THEN the glyph pulses beside the count, and goes once the fill stops

## REMOVED Requirements

### Requirement: An account chooses which bodies it keeps offline
Replaced by steps and the frontier (An account's mail comes in steps, Bodies download behind the list): no offline policy and no metered switch. The `mail-download-window` delta removes it too.
