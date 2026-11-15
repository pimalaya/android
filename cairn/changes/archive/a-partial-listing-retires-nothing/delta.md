---
cairn: delta
change: a-partial-listing-retires-nothing
---

Folds into spec/mail.md and spec/offline-store.md.

## ADDED Requirements

None.

## MODIFIED Requirements

### Requirement: A Graph mailbox keeps one unfiltered delta link
A Graph mailbox SHALL keep one message delta link made with no filter, selecting the id, `sentDateTime`, `isRead` and `flag`, every request asking 1,000 a page (`Prefer: odata.maxpagesize`), so its checkpoint is bound to no scope. `sentDateTime` is the instant the server stamped at submission, which Graph filters on exactly but which need not be the `Date` header a member is in scope by, so a round keeping or dropping a member by it SHALL be narrowed (A narrowed round enumerates its collection). Its mail SHALL be listed by band: a first chunk, a step, a widening and the fill SHALL list only the band they lack by `/messages` filtered on `sentDateTime` (from the floor, below the ceiling), newest first, up to 1,000 a page with the summary `$select`, a page resuming below the oldest `sentDateTime` the pages before reached rather than by `$skip`, and below that second once a whole page of it was listed already. A first chunk SHALL land without waiting on the delta link: the round a later pass opens over the scope the store covers SHALL make it, walking the delta's first pass a page at a time, listing by id and markers the members whose `sentDateTime` lies in the scope, and closing with the link, so what changed or went between the chunk and the link is read there. Under a scope with a bound that round SHALL be narrowed on every page, its last page handing the folder's every id; under an open scope it drops nothing and SHALL NOT be narrowed. A delta SHALL apply a removal whatever the date, drop a change to a message whose `sentDateTime` lies out of the scope, and list one in scope by id and markers alone: a message the store binds keeps its summary, any other one is read by id with the summary `$select`, 20 to a `$batch`. An expired link (410), or one made under a filter by an earlier version, SHALL open the round that makes a new one, relisting no band.

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

#### Scenario: A message with no Date submitted before the scope
- GIVEN a Graph inbox the phone holds, its link not made yet, with a message stating no `Date` whose `sentDateTime` lies before the scope
- WHEN the round that makes the link runs
- THEN the message stays on the phone, the folder's ids naming it

### Requirement: A narrowed round enumerates its collection
A mail round whose listing a date filter narrowed, the server's or the app's own check of a date other than the `Date` header (Graph's `sentDateTime`), SHALL say so on every page, and its last page SHALL hand every handle the mailbox holds, read with no date filter: IMAP `UID SEARCH ALL`, JMAP `Email/query` filtered by the mailbox alone and paged until the `total` the server states or, stating none, an empty page, Graph the folder's message ids, Gmail the label's ids with no query. An enumeration ending short of the total its server states SHALL be handed as none. A member the narrowed listing did not name SHALL be retired only when this enumeration does not name it either, and never by a round handing none. The engine of io-pimdir 0.12 (sync-draft-11) still finds absent a narrowed round's member dated before the round opened, whatever the enumeration holds: until the app takes the io-pimdir that reads the enumeration alone, the app stands short of this requirement by it, a Graph message whose `Date` lies in the scope and whose `sentDateTime` does not among what it retires.

#### Scenario: An undated message under the download window
- GIVEN a Gmail label holding a message received before the round's `after:`
- WHEN the round's last page lands
- THEN the message stays, the label's ids naming it

#### Scenario: A JMAP server stating no total
- GIVEN a JMAP mailbox of 1,200 messages under the download window, on a server whose `Email/query` states no `total`
- WHEN a round's last page lands
- THEN its enumeration names all 1,200, and none the round left unlisted is retired

## REMOVED Requirements

None.
