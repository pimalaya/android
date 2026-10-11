---
cairn: delta
change: mail-load-more-jmap
---

Folds into `spec/mail.md`. The intro's fourth paragraph's JMAP sentence becomes:

> A JMAP account is stepped through as one account: a step is one `Email/query` over its mailboxes but the trash and the junk, anchored where the last one stopped, each `Email` read once and placed in every mailbox its `mailboxIds` names, and every pass after replays `Email/changes` once from the state read before the first step, each mailbox projecting it.

Its third paragraph's first sentence becomes:

> Every mailbox but Gmail's and JMAP's runs io-pimdir's sync, on a session of the pass's pool, from its floor: its first pass lists its 50 newest messages, the floor being the oldest `Date` among them, and a background fill widens it a chunk of messages at a time toward the account's bound, all of its mail or the last N months on the `Date` header.

## ADDED Requirements

### Requirement: A JMAP step is one anchored query
A JMAP step SHALL be one `Email/query` over the account with `inMailboxOtherThan` the trash and the junk (`inMailbox` for a view filtered to one mailbox), sorted by `sentAt` descending when the account's mail capability lists it in `emailQuerySortOptions`, else by `receivedAt`, with `calculateTotal` and `collapseThreads` false and a limit of about 50, its `Email/get` back-referenced for the summary properties, `receivedAt` and `blobId`. The account's `Email` state SHALL be read before it, as the checkpoint. A step SHALL answer the ids its query reached last, oldest first, and the next step SHALL query from the first of them with `anchorOffset` 1; a query refused with `anchorNotFound` SHALL be asked again from the next one, and once none is left SHALL page by `receivedAt` below the frontier, two days of margin above it. Each `Email` the store does not hold SHALL be downloaded by its blob, one after another on the account's session, and placed in every mailbox its `mailboxIds` names; one the store holds SHALL be passed over, a window held whole moving on to the next, a few queries at most. A body that fails SHALL leave the anchors before it, so the next step reads it again. The app SHALL keep the anchors beside the account's frontier, per account and per mailbox a view steps alone, and SHALL save them only once the step landed. A JMAP account SHALL be listed account-wide, as a Gmail one is.

#### Scenario: A second step
- GIVEN a JMAP account whose first step brought its 50 newest messages
- WHEN *Load more* is tapped
- THEN one query anchored on the oldest of them answers the next 50, read whole

#### Scenario: The anchor deleted
- GIVEN the oldest message of the last step deleted on the server since
- WHEN the next step runs
- THEN its query is asked again from the message before it, and no message is skipped

#### Scenario: Two mailboxes
- GIVEN a JMAP message filed in the inbox and in Clients, not on the phone
- WHEN a step brings it
- THEN it is downloaded once, and both mailboxes hold it whole

### Requirement: A JMAP delta is read once per pass
The mailboxes of a JMAP pass SHALL share one replay of `Email/changes` from a given state, its touched messages read once with the summary properties, each mailbox taking as members those whose `mailboxIds` name it and as gone the others and the destroyed ones. A state the server can no longer compute changes from SHALL be refused for the engine to open a round, as before.

#### Scenario: A pass over five mailboxes
- GIVEN a JMAP account of five mailboxes, one message moved since the last pass
- WHEN a pass runs
- THEN `Email/changes` is asked once, the message is read once, and it leaves one mailbox for the other

## MODIFIED Requirements

### Requirement: A mailbox is stored whole
A mail round SHALL list every message of a mailbox within its scope (its floor, within the account's bound), newest first in the source's own recency order, a page at a time (500 UIDs per IMAP `UID FETCH`, 1,000 per Graph `/messages` page, 500 ids per Gmail `messages.list`, 500 per JMAP `Email/query` capped by the server's `maxObjectsInGet`), each page landing in one write. Every message a page lists SHALL arrive named by the summary and sort key of pimdir STORAGE Annex A read in the listing itself, with no body: IMAP from `FLAGS`, `RFC822.SIZE` and the header fields Annex A reads, `Content-Type` among them and no `BODYSTRUCTURE`; Graph from the summary `$select`, or for a delta's member the store does not bind, from that `$select` read by id; JMAP from `Email/get`'s summary properties. A Gmail round, which a history Gmail no longer holds or a label new to the store opens, SHALL list the label's ids received since the oldest reception the account holds, named by their markers alone (the unread and starred labels' ids), the store keeping the summary it holds and reading by metadata only the members it holds in another mailbox or that arrived ahead of every message it holds, never one read per held message. An interrupted round SHALL resume from the cursor its last landed page left, and a cursor the source refuses SHALL restart the round. A round's last page SHALL retire only what it found absent within its scope; mail outside it SHALL never be deleted by a sync. The chunks and the fill (A mailbox is listed a chunk at a time, Older mail fills in behind) SHALL bring a mailbox whole within the account's bound, except on Gmail and JMAP, whose steps decide what it holds.

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
A mailbox's first pass SHALL list its newest messages alone: the scope's floor SHALL be the oldest `Date` among the 50 newest the source names (the last 50 UIDs `UID SEARCH` finds, Graph's `$top` ordered by `sentDateTime`), or no floor when it holds fewer, within the account's bound. A chunk SHALL be a number of messages, never a span of time, and the floor SHALL be all that is kept of it: the coverage of the round that listed it. A pass after the first SHALL list from that coverage, or from the round under way, so it is a delta, once a Graph mailbox's delta link is made (A Graph mailbox keeps one unfiltered delta link). Widening a mailbox SHALL take the next chunk below its floor, the oldest `Date` among the newest messages dated before it, and list only the band it lacks, on every backend: no checkpoint is bound to a scope. A Gmail or JMAP mailbox SHALL be neither chunked nor widened: its account's steps bring what it holds.

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


### Requirement: Older mail fills in behind
After a mail tab's first sync, and on every return to the app or pass after it, every mailbox but Gmail's and JMAP's SHALL widen 500 messages at a time toward its account's bound, with no dialog, the inbox and the sent mail first, a mailbox never listed before one that only lacks older mail, and among them the one holding the most recent floor; a step SHALL widen the next mailboxes in that order side by side, as many of an account as its pool runs. A step SHALL list nothing while the body step has bodies left that it may download now. The fill SHALL run only while the app is in the foreground, on Wi-Fi, and while no other sync runs; it SHALL stop on an error, and SHALL resume from the floors the store covers. A Gmail or JMAP account SHALL NOT be filled: its search beyond what its steps brought is the server's.

#### Scenario: Leaving the app
- GIVEN a fill under way
- WHEN the app goes to the background
- THEN no further chunk is listed, and the next return resumes below the floors reached

#### Scenario: A metered network
- GIVEN a phone on mobile data
- WHEN the app is open
- THEN no chunk is listed until Wi-Fi is back


### Requirement: An account's frontier is where its steps reached
Each mail account SHALL keep a frontier, as app state beside the account: the `Date` of the oldest message its steps brought, leaving out a message dated more than a day before it was received (an import, a wrong clock), which is held and shown once the list's floor passes it; the oldest of them when every one is such. A step SHALL only move the frontier back; only the account's settings move it later (A window moved later frees the bodies below it). The merged list's floor SHALL be the most recent of the shown accounts' frontiers and of the shown mailboxes' coverage floors, a Gmail or JMAP mailbox bringing no coverage floor.

#### Scenario: An imported message
- GIVEN a step bringing messages dated from 8 to 9 October and one dated March 2019 received on 9 October
- WHEN it ends
- THEN the frontier is 8 October, the March 2019 message held and not listed above it


### Requirement: Bodies cross the bridge as files
A message read whole for the store SHALL cross the bridge as a file: `fetchMessageSources(handle, collection, ids, dir)` SHALL write one file per message into `dir` and answer each one's id, path and size (with its labels and reception on Gmail), a message gone or not handed over left out, an expired token failing the call. It SHALL dispatch per backend, a backend without batches of its own reading one message after another; JMAP SHALL read the `blobId`s of every id in one `Email/get` (chunked by `maxObjectsInGet`) and download the blobs one after another, never one `Email/get` per message. The file SHALL be filed in the blob store under its hash and handed to the engine as a body already persisted. Opening one message SHALL keep its own path.

#### Scenario: A batch of bodies
- GIVEN ten Gmail messages to download
- WHEN the body step reads them
- THEN one batch request answers them, each written to its own file, and no body crosses as a string


#### Scenario: JMAP bodies
- GIVEN ten JMAP messages to download
- WHEN the body step reads them
- THEN one `Email/get` names their blobs, and each blob is downloaded into its own file

## REMOVED Requirements

None.
