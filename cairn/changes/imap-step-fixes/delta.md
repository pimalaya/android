---
cairn: delta
change: imap-step-fixes
---

Folds into `spec/mail.md`. In the intro's third paragraph, "the floor being the `Date` of the 50th by `Date` on IMAP" stays; the requirements below change.

## ADDED Requirements

### Requirement: A link id names a message across mailboxes only where the account lists them as one
A message's link id SHALL be its handle. Only on an account listed account-wide (Gmail, JMAP), whose id names one message across its mailboxes, SHALL a body be linked from another mailbox under the same link id, and only from the same account (pimdir STORAGE §14). On IMAP a link id is a UID, naming a message in its own mailbox alone, and on Graph a folder's copy alone: no body SHALL be linked there, every body being fetched. Once per such account, every body its mailboxes hold under a link id another collection holds with the same body SHALL be released back to its summary, both sides, unless the message still needs it (a conflict, a pending create, a local edit), so the body step reads it again.

#### Scenario: Two IMAP mailboxes holding the same UIDs
- GIVEN an IMAP account whose inbox and archive both hold UIDs 1 to 120
- WHEN a step reads the bodies of UID 120 in both
- THEN each holds its own message's body, both read from the server

#### Scenario: A store upgraded over wrongly linked bodies
- GIVEN an IMAP account whose sent UID 120 holds the inbox UID 120's body
- WHEN its next pass lists its mailboxes
- THEN both bodies are released, and the body step reads each message's own

### Requirement: An IMAP step widens from the account floor
An IMAP account's first step SHALL read the 50 newest `Date`s of each mailbox it brings bodies to (A mailbox is listed a chunk at a time), take the 50th newest of them all as its floor, list every mailbox from that floor, the junk and the trash included, so each next pass is a delta, leave that floor as the frontier (all mail when those mailboxes hold 50 or fewer in all), then read whole every message it listed from that floor but the junk's and the trash's: about 50 bodies whatever the mailboxes. A later step SHALL widen the mailbox holding the most recent coverage floor by 50 until about 50 stored messages lie between it and the frontier, move the frontier to the `Date` of the 50th of them by `Date`, ties included, then read whole the messages between the new frontier and the old, one mailbox after another.

#### Scenario: A first IMAP step
- GIVEN an IMAP account whose inbox holds 3,000 messages, one an hour, and whose sent mail holds one every three hours
- WHEN its first step runs
- THEN the account's 50 newest by `Date`, about 37 of the inbox and 13 sent, are on the phone whole, and every mailbox is opened for the next pass's delta

#### Scenario: Load more on IMAP
- GIVEN an IMAP account after its first step
- WHEN *Load more* is tapped
- THEN about 50 older messages are listed and read whole, the frontier moved to the oldest of them

## MODIFIED Requirements

### Requirement: A mailbox is listed a chunk at a time
A mailbox's first pass SHALL list its newest messages alone: the scope's floor SHALL be, on IMAP, the `Date` of its 50th newest message by `Date`, and elsewhere the oldest `Date` among the 50 newest the source names (Graph's `$top` ordered by `sentDateTime`), or no floor when it holds fewer, within the account's bound. On an IMAP server advertising `SORT` (RFC 5256), the floor SHALL be read from `UID SORT (REVERSE DATE) UTF-8 SENTBEFORE` a day above the ceiling bounded by `SENTSINCE` 90 days below it, widened to a year then to all mail until 50 dated messages are found; on one without, from a sample of the newest UIDs read for their header fields, 50 then doubled at most four times (800 at most), until the last part read holds no message dated or received at or above the 50th newest `Date` of the sample, the floor that 50th. A chunk SHALL be a number of messages, never a span of time, and the floor SHALL be all that is kept of it: the coverage of the round that listed it. A pass after the first SHALL list from that coverage, or from the round under way, so it is a delta, once a Graph mailbox's delta link is made (A Graph mailbox keeps one unfiltered delta link). Widening a mailbox SHALL take the next chunk below its floor, the same way below it, and list only the band it lacks, on every backend: no checkpoint is bound to a scope. A Gmail or JMAP mailbox SHALL be neither chunked nor widened: its account's steps bring what it holds.

#### Scenario: A first pass over a large mailbox
- GIVEN a mailbox of 3,000 messages never listed
- WHEN it is synced
- THEN its 50 newest are listed, and its floor is the `Date` of the 50th of them

#### Scenario: An imported message among the newest UIDs
- GIVEN an IMAP inbox whose newest UID is a message dated 2019, received today
- WHEN its floor is read
- THEN the floor is the `Date` of its 50th newest message by `Date`, not 2019

#### Scenario: An import above this autumn's mail, without SORT
- GIVEN a Gmail IMAP inbox whose 300 newest UIDs are imported mail of 2025, each received when dated, above 100 messages of this autumn
- WHEN its floor is read
- THEN the floor is the `Date` of the 50th newest of this autumn's, read from at most 800 headers

#### Scenario: A small mailbox
- GIVEN a mailbox of 20 messages
- WHEN it is synced the first time
- THEN all of them are listed, and nothing is left below its floor

#### Scenario: Widening
- GIVEN a mailbox listed down to its 50th newest message
- WHEN it is widened
- THEN the next 50 below the floor are listed, the band alone

### Requirement: Bodies download behind the list
For each account that is on, the app SHALL raise to `Full`, through the engine's upgrade, every listed message dated on or after its mailbox's frontier in every mailbox but the junk and the trash, every listed message of a mailbox kept whole, and every owed message (Arrivals download whatever their date), after each pass, step and fill step while the app is open: newest first, the mailboxes the mail filter shows before the account's others, the owed ones last, at most 24 bodies an account a step. A step's own messages SHALL be stored from the files it read, never read again. A message with no date SHALL be downloaded only under a frontier of all mail or in a mailbox kept whole, and a pending create never. Whether a message is taken SHALL be read again against the current frontier as its chunk downloads. A body the store already holds under the same link id SHALL be linked rather than fetched where the link id names the message across mailboxes (A link id names a message across mailboxes only where the account lists them as one). The body SHALL be stored as the bytes the server sent, its base moved to it, its summary and sort key kept, its attachment mark restated from its parts. The step SHALL run only while the app is in the foreground, no other sync runs, and on Wi-Fi; it SHALL never be periodic work, and SHALL stop when the user starts a sync or leaves the app. A body that fails SHALL be tried again in the run (A body that fails is tried again in the run).

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

### Requirement: An IMAP step widens from the inbox floor
Replaced by An IMAP step widens from the account floor.
