---
cairn: delta
change: mail-sync-robustness
---

Folds into `spec/mail.md`.

## ADDED Requirements

### Requirement: A dead connection is dropped, never read again
The transport SHALL drop an origin's connection on any read or write failure, a read timeout included, and at the end of its stream, and the bridge SHALL tell it to on any transport error, on every backend. Before a new HTTP request on a held connection, the connection SHALL be dropped unless the last answer on it was read to its end by its framing (`Content-Length` or chunked) and did not say `Connection: close`, so no answer, late or cut short, is ever read as another request's. A reused connection that ends or fails before the first byte of an answer to an idempotent request (GET, HEAD, OPTIONS, PROPFIND, REPORT, PUT, DELETE) SHALL be reopened and the request sent again once (RFC 9112 section 9.3.1); any other request SHALL fail. A verb on a held session that fails after its connection was lost SHALL be run once more on a reopened session, an IMAP one greeted and authenticated again; a verb a live server refuses SHALL NOT be run again.

#### Scenario: Graph after two idle minutes
- GIVEN a Graph session idle for two minutes, its connection closed by the server
- WHEN the body step reads the next message
- THEN the read goes out again on a new connection, and the body is stored

#### Scenario: An answer that came too late
- GIVEN a read that timed out before its answer arrived
- WHEN the next request goes out
- THEN it goes on a new connection, and the late answer is never read as its own

#### Scenario: A dead IMAP connection
- GIVEN an IMAP session whose socket the server closed
- WHEN the next verb runs on it
- THEN the session is opened again and the verb answers

### Requirement: A body that fails is tried again in the run
A body the server does not hand over SHALL be put back at the end of its account's plan and tried again later in the same run, on a reopened session, at most 3 times a run. A message whose body failed every attempt SHALL say so in its row (*Download failed*) until a later run brings it. A download failing as a whole SHALL put its messages back the same way, an expired token renewed when the session reopens; an account whose download fails as a whole twice in a row SHALL leave the run.

#### Scenario: One body refused once
- GIVEN a body step whose server fails one message's read once
- WHEN the run goes on
- THEN that message is read again after the others and stored

#### Scenario: A token expiring mid-run
- GIVEN an account whose token expires while its bodies download
- WHEN the next chunk is read
- THEN the token is renewed and the run goes on

### Requirement: Junk and trash list when opened
The mail list showing an account's junk or trash alone SHALL list that mailbox once per opening, on Wi-Fi and while no other sync runs: a Gmail one by its label (`labelIds` `SPAM` or `TRASH` with `includeSpamTrash`), a step over that mailbox bringing about 50 messages it does not hold; any other by a pass over that mailbox.

#### Scenario: Gmail's spam
- GIVEN a Gmail account whose spam was never listed
- WHEN the mail list is filtered to its spam
- THEN its newest messages are listed

## MODIFIED Requirements

### Requirement: An account's frontier is where its steps reached
Each mail account SHALL keep a frontier, as app state beside the account: the `Date` of the oldest message its steps brought, leaving out a message dated more than a day before it was received (an import, a wrong clock), which is held and shown once the list's floor passes it; the oldest of them when every one is such. A step over one mailbox alone SHALL move that mailbox's own frontier and never the account's, and a mailbox's frontier SHALL be the older of its own and its account's. A step SHALL only move a frontier back; only the account's settings move it later (A window moved later frees the bodies below it), a mailbox's own frontier older than the account's new one then going. The merged list's floor SHALL be the most recent of the shown mailboxes' frontiers and of their coverage floors, a Gmail mailbox bringing no coverage floor.

#### Scenario: An imported message
- GIVEN a step bringing messages dated from 8 to 9 October and one dated March 2019 received on 9 October
- WHEN it ends
- THEN the frontier is 8 October, the March 2019 message held and not listed above it

#### Scenario: A step over one label
- GIVEN a Gmail account whose frontier is 1 October, its view filtered to the label Clients
- WHEN *Load more* brings Clients down to 1 September
- THEN Clients lists down to 1 September, and the merged view still ends at 1 October

### Requirement: An account's mail comes in steps
An account's mail SHALL come in steps: a step SHALL bring about 50 messages of the account whole (headers and body, plain `Full`), older than what the account holds, never counted by an offset, on the account's one session, its reads in small batches one after another. Each backend SHALL decide how a step finds them and batches them. The first sync SHALL be one step. A step SHALL leave out the junk and the trash. A view filtered to one mailbox of an account that has several SHALL step that mailbox alone. A step finding nothing older SHALL leave the account at all mail, or the one mailbox it stepped alone.

#### Scenario: A first sync
- GIVEN an account just connected, on Wi-Fi
- WHEN the mail tab's first sync runs
- THEN about 50 messages are listed and readable offline when it ends

#### Scenario: A small account
- GIVEN an account holding 30 messages
- WHEN its first step runs
- THEN all 30 are on the phone, and the list has nothing more to load

### Requirement: The mail list ends on Load more
While a shown account that is on has more to load, the mail list SHALL end on a footer: a button, *Load more*, over a line saying about how many messages a step brings each account (*About 50 more per account*). An account shown by one of its mailboxes alone has more to load while that mailbox's frontier is not all mail. A tap SHALL take one step of each such account, one after another under the sync strip, then read the list again and download the bodies left. Off Wi-Fi the line SHALL add that it waits for Wi-Fi and the button SHALL be disabled. Junk, trash and mailboxes kept whole SHALL take no part. The footer SHALL hide under a search, and SHALL stand in for an empty list's empty state.

#### Scenario: Two accounts
- GIVEN accounts A and B shown together, both with older mail
- WHEN *Load more* is tapped
- THEN A then B take a step, and the list reaches down to the more recent of their new frontiers

#### Scenario: Off Wi-Fi
- GIVEN a list on mobile data
- WHEN its end is reached
- THEN the footer says it waits for Wi-Fi, and its button does nothing

### Requirement: Arrivals download whatever their date
A message a delta lists new to the store (IMAP CONDSTORE or QRESYNC, Gmail history, Graph delta, JMAP changes), and a message a step brought whose body did not land, SHALL be owed: its body SHALL download on Wi-Fi whatever its `Date`, a delayed message or one filed from older mail included, its row not saying *Not downloaded* meanwhile. What is owed SHALL be kept beside the account and survive a restart; one in a mailbox that downloads nothing (junk, trash) SHALL be dropped.

#### Scenario: A delayed message
- GIVEN an account whose frontier is 1 October
- WHEN a pass lists a new message dated 20 September
- THEN its body downloads with the others

#### Scenario: Across a restart
- GIVEN an arrival dated below the frontier, its body not downloaded when the app was closed
- WHEN the app is opened again on Wi-Fi
- THEN its body downloads

### Requirement: Bodies download behind the list
For each account that is on, the app SHALL raise to `Full`, through the engine's upgrade, every listed message dated on or after its mailbox's frontier in every mailbox but the junk and the trash, every listed message of a mailbox kept whole, and every owed message (Arrivals download whatever their date), after each pass, step and fill step while the app is open: newest first, the mailboxes the mail filter shows before the account's others, the owed ones last, at most 24 bodies an account a step. A step's own messages SHALL be stored from the files it read, never read again. A message with no date SHALL be downloaded only under a frontier of all mail or in a mailbox kept whole, and a pending create never. Whether a message is taken SHALL be read again against the current frontier as its chunk downloads. A body the store already holds under the same link id SHALL be linked rather than fetched. The body SHALL be stored as the bytes the server sent, its base moved to it, its summary and sort key kept, its attachment mark restated from its parts. The step SHALL run only while the app is in the foreground, no other sync runs, and on Wi-Fi; it SHALL never be periodic work, and SHALL stop when the user starts a sync or leaves the app. A body that fails SHALL be tried again in the run (A body that fails is tried again in the run).

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

## REMOVED Requirements
