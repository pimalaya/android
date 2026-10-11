---
cairn: delta
change: mail-load-more-graph
---

Folds into `spec/mail.md`.

## ADDED Requirements

### Requirement: A Graph step lists the account by date
A Graph step SHALL list the account's messages (`/me/messages`) newest `Date` first (`$orderby=sentDateTime desc`), selecting the id, `sentDateTime`, `receivedDateTime`, `parentFolderId` and the MAPI size, filtered `sentDateTime le F` on the frontier (a view filtered to one folder: that folder's own) and from the account's bound, about 50 a request and some slack, keeping messages until it holds 50 and every one sharing the 50th one's `Date`. It SHALL leave out the messages the store holds dated `F` (the step before brought them), and those in the trash, the junk, a mailbox kept whole or a folder outside the roster. A request answering fewer than it asked for SHALL end the step with nothing older left; otherwise the next request SHALL list from the oldest `Date` reached, that second included and the ids already seen left out, never through `nextLink`, and below it once a whole page of that second was seen. An answer naming `InefficientFilter`, or a 504, SHALL list each folder the same way and merge them by `Date`. A view filtered to one folder SHALL list that folder alone. Every folder stepped SHALL then be listed by band down to the oldest `Date` the step brought, the folders holding its messages first, and the step's bodies stored from what it read.

#### Scenario: Three folders
- GIVEN a Graph account whose 50 newest messages lie in Inbox, Sent Items and Projets, and older ones in the trash
- WHEN a step runs
- THEN one listing names the 50, the trash's left out, and each of the three folders is listed by band down to the oldest of them

#### Scenario: A busy second
- GIVEN a Graph account whose 50th newest message shares its `Date` with four older ones
- WHEN a step runs
- THEN it brings 54, and the next step starts below them

### Requirement: Graph bodies come twenty to a batch
A Graph account SHALL read bodies through `$batch` calls of up to 20 `$value` GETs and about 4 MB of declared size, one call at a time on the account's session, a message over 1 MB, or whose size Graph does not state, read alone with a plain GET, as SHALL be one whose batched answer is not a base64 body. An item throttled inside a call (429) SHALL wait the `Retry-After` its own answer names, once, then go in the next call; an item failing again, or failing otherwise, SHALL be left out of the answer, and one gone (404) too. `fetchMessageSources` on Graph SHALL read the sizes first, 20 ids to a `$batch`.

#### Scenario: Fifty bodies
- GIVEN fifty messages of about 40 KB, one of 3 MB
- WHEN a step reads them
- THEN three batch calls read 49 of them and one GET the large one

#### Scenario: A throttled item
- GIVEN a batch answering one item 429 with `Retry-After: 2`
- WHEN the read goes on
- THEN it waits 2 s and reads that item again in the next call

## MODIFIED Requirements

### Requirement: A Graph mailbox keeps one unfiltered delta link
A Graph mailbox SHALL keep one message delta link made with no filter, selecting the id, `sentDateTime`, `isRead` and `flag`, every request asking 1,000 a page (`Prefer: odata.maxpagesize`), so its checkpoint is bound to no scope. Its mail SHALL be listed by band: a first chunk, a step, a widening and the fill SHALL list only the band they lack by `/messages` filtered on `sentDateTime` (from the floor, below the ceiling: the `Date` header, exactly), newest first, up to 1,000 a page with the summary `$select`, a page resuming below the oldest `Date` the pages before reached rather than by `$skip`, and below that second once a whole page of it was listed already. A first chunk SHALL land without waiting on the delta link: the round a later pass opens over the scope the store covers SHALL make it, walking the delta's first pass a page at a time, listing the members in scope by id and markers, and closing with the link, so what changed or went between the chunk and the link is read there. A delta SHALL apply a removal whatever the date, drop a change to a message dated out of the scope, and list one in scope by id and markers alone: a message the store binds keeps its summary, any other one is read by id with the summary `$select`, 20 to a `$batch`. An expired link (410), or one made under a filter by an earlier version, SHALL open the round that makes a new one, relisting no band.

#### Scenario: The first chunk does not wait on the link
- GIVEN a Graph inbox of 50,000 messages never listed
- WHEN the first sync fetches it
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

## REMOVED Requirements
