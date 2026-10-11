---
cairn: delta
change: mail-load-more-imap
---

Folds into `spec/mail.md`. The intro's third paragraph's first sentence becomes:

> Every mailbox but Gmail's runs io-pimdir's sync, on a session of the pass's pool, from its floor: its first pass lists its 50 newest messages, the floor being the `Date` of the 50th by `Date` on IMAP and the oldest `Date` among them elsewhere, and a background fill widens it a chunk of messages at a time toward the account's bound, all of its mail or the last N months on the `Date` header.

## ADDED Requirements

### Requirement: An IMAP step widens from the inbox floor
An IMAP account's first step SHALL take its inbox's floor (A mailbox is listed a chunk at a time), or the most recent of its other mailboxes' floors when the inbox holds fewer than 50 messages, list every mailbox from that floor, the junk and the trash included, so each next pass is a delta, leave that floor as the frontier (all mail when every mailbox holds fewer than 50), then read whole every message it listed from that floor but the junk's and the trash's. A later step SHALL widen the mailbox holding the most recent coverage floor by 50 until about 50 stored messages lie between it and the frontier, move the frontier to the `Date` of the 50th of them by `Date`, ties included, then read whole the messages between the new frontier and the old, one mailbox after another.

#### Scenario: A first IMAP step
- GIVEN an IMAP account whose inbox holds 3,000 messages and whose sent mail holds 17 from the last two days
- WHEN its first step runs
- THEN the inbox's 50 newest by `Date` and those 17 are on the phone whole, and every mailbox is opened for the next pass's delta

#### Scenario: Load more on IMAP
- GIVEN an IMAP account after its first step
- WHEN *Load more* is tapped
- THEN about 50 older messages are listed and read whole, the frontier moved to the oldest of them

### Requirement: An IMAP search is kept while its mailbox stands
The UIDs a `UID SEARCH` or `UID SORT` found SHALL be kept per mailbox, UIDVALIDITY and scope for the session, and the header fields a floor read reused by the round after it, until the mailbox's EXAMINE reports another UIDNEXT, HIGHESTMODSEQ or message count; header fields SHALL be kept only on a server reporting a HIGHESTMODSEQ, and a write SHALL drop what was kept.

#### Scenario: A round after a floor
- GIVEN a CONDSTORE server whose inbox floor was just read, no message arriving meanwhile
- WHEN the inbox is listed from that floor
- THEN no header field is read twice, and a page after the first searches no more

## MODIFIED Requirements

### Requirement: A mailbox is listed a chunk at a time
A mailbox's first pass SHALL list its newest messages alone: the scope's floor SHALL be, on IMAP, the `Date` of its 50th newest message by `Date`, and elsewhere the oldest `Date` among the 50 newest the source names (Graph's `$top` ordered by `sentDateTime`, JMAP's `Email/query` by `receivedAt`), or no floor when it holds fewer, within the account's bound. On an IMAP server advertising `SORT` (RFC 5256), the floor SHALL be read from `UID SORT (REVERSE DATE) UTF-8 SENTBEFORE` a day above the ceiling bounded by `SENTSINCE` 90 days below it, widened to a year then to all mail until 50 dated messages are found; on one without, from the newest UIDs, a message whose `Date` is more than a day before its `INTERNALDATE` counting toward the 50 without lowering the floor, unless every one of them is such. A chunk SHALL be a number of messages, never a span of time, and the floor SHALL be all that is kept of it: the coverage of the round that listed it. A pass after the first SHALL list from that coverage, or from the round under way, so it is a delta, once a Graph mailbox's delta link is made (A Graph mailbox keeps one unfiltered delta link). Widening a mailbox SHALL take the next chunk below its floor, the same way below it, and list only the band it lacks, on every backend: no checkpoint is bound to a scope. A Gmail mailbox SHALL be neither chunked nor widened: its account's steps bring what it holds.

#### Scenario: A first pass over a large mailbox
- GIVEN a mailbox of 3,000 messages never listed
- WHEN it is synced
- THEN its 50 newest are listed, and its floor is the `Date` of the 50th of them

#### Scenario: An imported message among the newest UIDs
- GIVEN an IMAP inbox whose newest UID is a message dated 2019, received today
- WHEN its floor is read
- THEN the floor is the `Date` of its 50th newest message by `Date`, not 2019

#### Scenario: A small mailbox
- GIVEN a mailbox of 20 messages
- WHEN it is synced the first time
- THEN all of them are listed, and nothing is left below its floor

#### Scenario: Widening
- GIVEN a mailbox listed down to its 50th newest message
- WHEN it is widened
- THEN the next 50 below the floor are listed, the band alone

### Requirement: Bodies cross the bridge as files
A message read whole for the store SHALL cross the bridge as a file: `fetchMessageSources(handle, collection, ids, dir)` SHALL write one file per message into `dir` and answer each one's id, path and size (with its labels and reception on Gmail), a message gone or not handed over left out, an expired token failing the call. It SHALL dispatch per backend, a backend without batches of its own reading one message after another. On IMAP it SHALL read the sizes (`UID FETCH <set> (UID RFC822.SIZE)`), then one streamed `UID FETCH <set> (UID BODY.PEEK[])` per batch of at most 50 messages and about 12 MB, a message past it alone, each body written to its file as it arrives; a batch the server refuses SHALL be read one message after another. The file SHALL be filed in the blob store under its hash and handed to the engine as a body already persisted. Opening one message SHALL keep its own path.

#### Scenario: A batch of bodies
- GIVEN ten Gmail messages to download
- WHEN the body step reads them
- THEN one batch request answers them, each written to its own file, and no body crosses as a string

#### Scenario: An IMAP batch
- GIVEN 40 IMAP messages of an inbox to download
- WHEN the body step reads them
- THEN one `UID FETCH` of their sizes and one streamed `UID FETCH` of their bodies answer them, each written to its own file

## REMOVED Requirements
