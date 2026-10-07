---
cairn: delta
change: graph-unfiltered-delta
---

## ADDED Requirements

### Requirement: A Graph mailbox keeps one unfiltered delta link
A Graph mailbox SHALL keep one message delta link made with no filter, selecting the id, `sentDateTime`, `isRead` and `flag`, every request asking 1,000 a page (`Prefer: odata.maxpagesize`), so its checkpoint is bound to no scope. Its mail SHALL be listed by band: a first chunk, a widening and the fill SHALL list only the band they lack by `/messages` filtered on `sentDateTime` (from the floor, below the ceiling: the `Date` header, exactly), newest first, up to 1,000 a page with the summary `$select`, a page resuming below the oldest `Date` the pages before reached rather than by `$skip`. A first chunk SHALL land without waiting on the delta link: the round a later pass opens over the scope the store covers SHALL make it, walking the delta's first pass a page at a time, listing the members in scope by id and markers, and closing with the link, so what changed or went between the chunk and the link is read there. A delta SHALL apply a removal whatever the date, drop a change to a message dated out of the scope, and list one in scope by id and markers alone: a message the store binds keeps its summary, any other one is read by id with the summary `$select`, 20 to a `$batch`. An expired link (410), or one made under a filter by an earlier version, SHALL open the round that makes a new one, relisting no band.

#### Scenario: The first chunk does not wait on the link
- GIVEN a Graph inbox of 50,000 messages never listed
- WHEN the first dialog syncs it
- THEN its 50 newest are listed by `/messages` and no delta is asked for
- AND the next pass names the 50,000 by id once and keeps the link

#### Scenario: Widening relists nothing
- GIVEN a Graph inbox listed down to its 50th newest message, its delta link made
- WHEN it is widened three times
- THEN 150 more messages are listed, the band alone each time, and the link is kept

#### Scenario: A change out of scope
- GIVEN a Graph inbox listed down to September
- WHEN a message from March is marked read elsewhere
- THEN the next delta reports it, and nothing is stored

#### Scenario: A message gone before the link
- GIVEN a Graph inbox whose first chunk is listed, its link not made yet
- WHEN a message of the chunk is deleted elsewhere
- THEN the pass that makes the link removes it

## MODIFIED Requirements

### Requirement: A mailbox is stored whole
A mail round SHALL list every message of a mailbox within its scope (its floor, within the account's bound), newest first in the source's own recency order, a page at a time (500 UIDs per IMAP `UID FETCH`, 1,000 per Graph `/messages` page, 100 ids per Gmail `messages.list`, 500 per JMAP `Email/query` capped by the server's `maxObjectsInGet`), each page landing in one write. Every message a page lists SHALL arrive named by the summary and sort key of pimdir STORAGE Annex A read in the listing itself, with no body: IMAP from `FLAGS`, `RFC822.SIZE` and the header fields Annex A reads, `Content-Type` among them and no `BODYSTRUCTURE`; Graph from the summary `$select`, or for a delta's member the store does not bind, from that `$select` read by id; Gmail from its metadata read; JMAP from `Email/get`'s summary properties. An interrupted round SHALL resume from the cursor its last landed page left, and a cursor the source refuses SHALL restart the round. A round's last page SHALL retire only what it found absent within its scope; mail outside it SHALL never be deleted by a sync. The chunks and the fill (A mailbox is listed a chunk at a time, Older mail fills in behind) SHALL bring a mailbox whole within the account's bound.

#### Scenario: A first pass over a large mailbox
- GIVEN a mailbox of 100k messages and an empty store
- WHEN it is synced
- THEN its newest chunk is listed with its subject, sender and date before the dialog closes
- AND the rest is listed behind it, a chunk at a time

#### Scenario: An interrupted first pass
- GIVEN a round cut off after its first page
- WHEN the mailbox is synced again
- THEN it resumes below the last landed page rather than from the top

#### Scenario: A bounded account
- GIVEN an account bounded to the last 6 months
- WHEN it is synced and filled
- THEN older messages are neither fetched nor listed, and none already stored is deleted

### Requirement: A mailbox is listed a chunk at a time
A mailbox's first pass SHALL list its newest messages alone: the scope's floor SHALL be the oldest `Date` among the 50 newest the source names (the last 50 UIDs `UID SEARCH` finds, Graph's `$top` ordered by `sentDateTime`, Gmail's `messages.list`, JMAP's `Email/query` by `receivedAt`), or no floor when it holds fewer, within the account's bound. A chunk SHALL be a number of messages, never a span of time, and the floor SHALL be all that is kept of it: the coverage of the round that listed it. A pass after the first SHALL list from that coverage, or from the round under way, so it is a delta, once a Graph mailbox's delta link is made (A Graph mailbox keeps one unfiltered delta link). Widening a mailbox SHALL take the next chunk below its floor, the oldest `Date` among the newest messages dated before it, and list only the band it lacks, on every backend: no checkpoint is bound to a scope.

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

## REMOVED Requirements
