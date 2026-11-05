---
cairn: delta
change: fill-from-two-queues
---

## ADDED Requirements

### Requirement: The first sync lists every ticked mailbox side by side
On an account other than Gmail, the mail tab's first sync SHALL list the first chunk of every mailbox the filter ticks side by side on the account's whole pool, the inbox begun first, each chunk written as it lands so the list shows it, and SHALL run no account-wide step. Once they are written, the account's frontier SHALL be the `Date` of the 25th newest message they hold, the junk's, the trash's and a mailbox kept whole's left out, ties included: any of the account's 25 newest is among its own mailbox's 25 newest, which that mailbox's first chunk holds. The frontier SHALL be all mail when they hold fewer and every one is listed to its bound. A mailbox the server refuses holds nothing and is left out; its messages above the frontier, listed later, are owed by the body step's rule. Until the frontier is set, the list SHALL show every first chunk down to the merged floor as each lands; the frontier then hides what lies below it. While the first chunks land, the dock's bar SHALL count the mailboxes listed and written. The account's 25 newest bodies SHALL then download within the same pass on one session, the bar running indeterminate and their rows dimmed until each lands; the pass SHALL end once they have, and the fill (Older mail fills in behind) SHALL then list the rest silently, with no dock. A body that does not land SHALL be owed to the body step (Bodies download behind the list).

#### Scenario: A first sync on IMAP
- GIVEN an IMAP account whose inbox holds a message an hour and whose sent mail one every three hours, both ticked, and its junk
- WHEN the mail tab's first sync runs
- THEN each mailbox lists 25 messages, and the frontier is the 25th newest of the inbox's and the sent mail's together: 19 of the inbox and 6 sent, read whole behind

#### Scenario: A small account
- GIVEN an account whose ticked mailboxes hold 24 messages in all
- WHEN the first sync runs
- THEN all 24 are listed and read whole, and the frontier is all mail

#### Scenario: A refused mailbox
- GIVEN an account whose sent mail the server refuses to open
- WHEN the first sync runs
- THEN the frontier is the inbox's 25th newest, and the sent mail is left unopened

### Requirement: Load more waits for the fill only when it lacks a step
While the fill lists an account's older envelopes, *Load more* SHALL show at once a step the store already holds below the frontier (above every stepped mailbox's floor, or every one whole); one it would have to list SHALL NOT run, a toast asking to try again in a few minutes. A Gmail step SHALL never wait.

#### Scenario: Load more early in the fill
- GIVEN a fill that has listed only first chunks
- WHEN *Load more* is tapped
- THEN a toast asks to wait, and nothing is listed

#### Scenario: Load more once a chunk landed
- GIVEN a fill still running whose chunks hold 25 messages below the frontier
- WHEN *Load more* is tapped
- THEN 25 more show at once, their bodies downloading within the pass

### Requirement: The mail list shows its background download
While bodies download in the background and no pass runs, the sync dock SHALL read *Loading older mail* beside its spinner, its second line counting the bodies landed over a bar of the run's plan, every account's summed, a body counting once its file is written and a replan adding to the plan. The bar SHALL never move back while the run goes on. The fill's listing of older envelopes SHALL show nothing. The dock SHALL show only once a body downloaded, and SHALL go when the run ends or pauses (the app left, no Wi-Fi).

#### Scenario: After the first mail sync
- GIVEN a first mail sync that landed the newest messages of an IMAP account
- WHEN the fill lists its older envelopes in the background
- THEN no dock shows

#### Scenario: Bodies scrolled past
- GIVEN 100 bodies left to download and the list scrolled down
- WHEN 48 have landed
- THEN the dock shows above the bottom bar, its bar near one half

## MODIFIED Requirements

### Requirement: A mailbox is listed a chunk at a time
A mailbox's first pass SHALL list its newest messages alone: the scope's floor SHALL be, on IMAP, the `Date` of its 25th newest message by `Date`, and elsewhere the oldest `Date` among the 25 newest the source names (Graph's `$top` ordered by `sentDateTime`), or no floor when it holds fewer, within the account's bound. On an IMAP server advertising `SORT` (RFC 5256), the floor SHALL be read from `UID SORT (REVERSE DATE) UTF-8 SENTBEFORE` a day above the ceiling bounded by `SENTSINCE` 90 days below it, widened to a year then to all mail until 25 dated messages are found; on one without, from a sample of the newest UIDs read for their header fields, 25 then doubled at most four times (400 at most), until the last part read holds no message dated or received at or above the 25th newest `Date` of the sample, the floor that 25th. A chunk SHALL be a number of messages, never a span of time, and the floor SHALL be all that is kept of it: the coverage of the round that listed it. A pass after the first SHALL list from that coverage, or from the round under way, so it is a delta, once a Graph mailbox's delta link is made (A Graph mailbox keeps one unfiltered delta link). Widening a mailbox SHALL take the next chunk below its floor, the same way below it, and list only the band it lacks, on every backend: no checkpoint is bound to a scope. A Gmail or JMAP mailbox SHALL be neither chunked nor widened: its account's steps bring what it holds.

#### Scenario: A first pass over a large mailbox
- GIVEN a mailbox of 3,000 messages never listed
- WHEN it is synced
- THEN its 25 newest are listed, and its floor is the `Date` of the 25th of them

#### Scenario: An imported message among the newest UIDs
- GIVEN an IMAP inbox whose newest UID is a message dated 2019, received today
- WHEN its floor is read
- THEN the floor is the `Date` of its 25th newest message by `Date`, not 2019

#### Scenario: An import above this autumn's mail, without SORT
- GIVEN a Gmail IMAP inbox whose 300 newest UIDs are imported mail of 2025, each received when dated, above 100 messages of this autumn
- WHEN its floor is read
- THEN the floor is the `Date` of the 25th newest of this autumn's, read from at most 400 headers

#### Scenario: A small mailbox
- GIVEN a mailbox of 20 messages
- WHEN it is synced the first time
- THEN all of them are listed, and nothing is left below its floor

#### Scenario: Widening
- GIVEN a mailbox listed down to its 25th newest message
- WHEN it is widened by a chunk
- THEN the chunk below the floor is listed, the band alone

### Requirement: Older mail fills in behind
Every account but Gmail's SHALL keep a listing queue of the mailboxes the filter ticks, in this order: every mailbox never listed first (the inbox, then the sent mail, then the others), then the inbox and the sent mail, then the others, each by the most recent floor. A listing job SHALL take the queue's head whenever it is free, list a mailbox never listed by its first chunk and any other by 500 messages below its floor toward its account's bound, with no dialog, and put it back once that chunk is written; a mailbox under way SHALL never be taken twice, and no job SHALL wait for another mailbox. The queue SHALL start after the mail tab's first sync and on every return to the app or pass after it. The listing SHALL start once the body step (Bodies download behind the list) has nothing left to download, so what a first sync or Load more showed reads whole first; a chunk written SHALL have the body step plan again, which then runs beside the listing on its own job. The fill SHALL run only while the app is in the foreground, on Wi-Fi, and while no other sync runs; it SHALL stop on an error, and SHALL resume from the floors the store covers. A Gmail account SHALL NOT be filled: its search beyond what its steps brought is the server's.

#### Scenario: Leaving the app
- GIVEN a fill under way
- WHEN the app goes to the background
- THEN no further chunk is listed, and the next return resumes below the floors reached

#### Scenario: A metered network
- GIVEN a phone on mobile data
- WHEN the app is open
- THEN no chunk is listed until Wi-Fi is back

#### Scenario: One slow mailbox
- GIVEN a Graph account whose inbox takes 8 s a chunk and four other mailboxes 4 s
- WHEN the fill runs on three listing jobs
- THEN the two jobs not on the inbox take the other mailboxes in turn while the inbox's chunk is under way

#### Scenario: Bodies left
- GIVEN bodies owed while older mail is left to list
- WHEN the fill runs
- THEN the body job downloads them while the listing jobs list

### Requirement: An account's mailboxes sync side by side
A mail pass and the background fill SHALL run an account's mailboxes concurrently on a pool of sessions, four for Graph and JMAP, three for IMAP, two for Gmail, each job on a session of its own that no other job uses while it runs, the jobs taking the mailboxes in the pass's order. While the fill lists, its listing jobs SHALL hold every session of the account but one, which the body step holds; once the listing is done, the body step SHALL hold the account's whole pool. A step SHALL run on the account's one session. Every storage load, lookup and write SHALL be answered by one writer at a time, so only the network overlaps. A mailbox that fails SHALL leave the others running, the pass reporting the first failure, but for a mailbox the server refuses to open (A step and a pass skip a mailbox the server refuses to open).

#### Scenario: A pass over twelve mailboxes on Graph
- GIVEN a Graph account of twelve mailboxes
- WHEN a pass runs
- THEN four mailboxes are listed at once, the inbox begun first

#### Scenario: The fill on Graph
- GIVEN a Graph account filling, bodies owed
- WHEN the fill runs
- THEN three mailboxes are listed at once and one session downloads bodies, four requests at most in flight

#### Scenario: One mailbox failing
- GIVEN a pass over five mailboxes, one of which fails on a dropped connection
- WHEN the pass runs
- THEN the other four are stored, and the pass reports the failure

#### Scenario: One mailbox refused
- GIVEN a pass over five mailboxes, one of which the server refuses to open
- WHEN the pass runs
- THEN the other four are stored, and the pass reports nothing

#### Scenario: Two pages landing together
- GIVEN two mailboxes whose pages arrive at the same moment
- WHEN both are written
- THEN one write lands whole before the other begins

### Requirement: An account's mail comes in steps
An account's mail SHALL come in steps: a step SHALL bring about 25 messages whole (headers and body, plain `Full`), older than what it holds, never counted by an offset, on the account's one session, its reads in small batches one after another. Each backend SHALL decide how a step finds them and batches them. The first sync of a Gmail account SHALL be one step of the account over every label but Trash and Spam; any other account's first sync SHALL list its ticked mailboxes side by side (The first sync lists every ticked mailbox side by side). After it, a Gmail account SHALL step as a whole (All Mail), and any other account mailbox by mailbox. A step SHALL leave out the junk and the trash. A view filtered to one mailbox of an account that has several SHALL step that mailbox alone. A step finding nothing older SHALL leave the account at all mail, or the one mailbox it stepped alone.

#### Scenario: A first sync
- GIVEN a Gmail account just connected, on Wi-Fi
- WHEN the mail tab's first sync runs
- THEN about 25 messages are listed and readable offline when it ends

#### Scenario: A small account
- GIVEN an account holding 20 messages
- WHEN its first sync runs
- THEN all 20 are on the phone, and the list has nothing more to load

### Requirement: An IMAP step widens from the account floor
An IMAP step SHALL widen the mailbox holding the most recent coverage floor by 25 until about 25 stored messages lie between it and the frontier, move the frontier to the `Date` of the 25th of them by `Date`, ties included, its bodies left to the body step (A step from the store).

#### Scenario: Load more on IMAP
- GIVEN an IMAP account after its first sync
- WHEN *Load more* is tapped
- THEN about 25 older messages show, the frontier moved to the oldest of them, and the body step reads them whole

### Requirement: A step from the store
An IMAP or Graph step SHALL move the frontier from the store (An IMAP step widens from the account floor), listing only what the store lacks, so the messages it brings show at once, their rows dimmed; *Load more* SHALL then download their bodies within its pass on one session, its bar indeterminate, one that does not land owed to the body step. *Load more* SHALL take one step per shown account, over the one mailbox the view shows when it shows one of several.

#### Scenario: Load more after the fill
- GIVEN a Graph account whose fill listed every envelope within its bound
- WHEN *Load more* is tapped
- THEN 25 more messages show at once, nothing listed from the server, dimmed until their bodies land within the pass

### Requirement: A step's size reads 25
In A mail step counts the bodies it brings, The mail list ends on Load more, and A Gmail step is a set difference, every count of the messages a step brings SHALL read 25 for 50 (the footer's *About 25 more per account*, a Gmail step keeping ids until it holds about 25), their scenarios alike; nothing else in them moves.

## REMOVED Requirements

### Requirement: A JMAP step is one anchored query
A JMAP account steps from the store as IMAP and Graph do (A step from the store); its fill lists what the anchored query read.

### Requirement: A Graph step lists the account by date
A Graph step steps from the store as IMAP does (A step from the store): the fill lists the envelopes the account-wide walk read.
