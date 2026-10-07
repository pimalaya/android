---
cairn: delta
change: parallel-mailboxes
---

## ADDED Requirements

### Requirement: An account's mailboxes sync side by side
A mail pass, the first-sync dialog, the scroll widening and the background fill SHALL run an account's mailboxes concurrently on a pool of sessions, four for Graph and JMAP, three for IMAP, two for Gmail, each worker on a session of its own that no other worker uses while it runs, the workers taking the mailboxes in the pass's order. Every storage load, lookup and write SHALL be answered by one writer at a time, so only the network overlaps. A mailbox that fails SHALL leave the others running, the pass reporting the first failure. The first-sync dialog SHALL close once every mailbox's first chunk has landed, saying how many of the account's mailboxes have.

#### Scenario: A first sync of twelve mailboxes on Graph
- GIVEN a Graph account of twelve mailboxes never listed
- WHEN its first sync runs
- THEN four mailboxes are listed at once, the inbox begun first, and the dialog closes once all twelve chunks have landed

#### Scenario: One mailbox refused
- GIVEN a pass over five mailboxes, one of which the server refuses
- WHEN the pass runs
- THEN the other four are stored, and the pass reports the refusal

#### Scenario: Two pages landing together
- GIVEN two mailboxes whose pages arrive at the same moment
- WHEN both are written
- THEN one write lands whole before the other begins

## MODIFIED Requirements

### Requirement: A pass takes the inbox first
Every mail pass SHALL take an account's mailboxes in the order of the role each source states: the inbox, the sent mail, the drafts, every other by name, the junk and the trash last, the workers of a pool beginning them in that order. The roles SHALL be stored as pimdir's collection roles (STORAGE section 14): RFC 6154 attributes and `INBOX` on IMAP, RFC 8621 roles, Gmail's system labels, Graph's well-known folders.

#### Scenario: A Graph account
- GIVEN Graph listing *Sent Items*, *Projets* and *Inbox* in that order
- WHEN the account is synced
- THEN *Inbox* is begun first and *Sent Items* second

### Requirement: Older mail fills in behind
After a mail tab's first sync, and on every return to the app or pass after it, every mailbox SHALL widen 500 messages at a time toward its account's bound, with no dialog, the inbox and the sent mail first, a mailbox never listed before one that only lacks older mail, and among them the one holding the most recent floor; a step SHALL widen the next mailboxes in that order side by side, as many of an account as its pool runs. The fill SHALL run only while the app is in the foreground, on a network that is not metered, and while no other sync runs; it SHALL stop on an error, and SHALL resume from the floors the store covers.

#### Scenario: Leaving the app
- GIVEN a fill under way
- WHEN the app goes to the background
- THEN no further chunk is listed, and the next return resumes below the floors reached

#### Scenario: A metered network
- GIVEN a phone on mobile data
- WHEN the first dialog closes
- THEN only the first chunks are stored, until an unmetered network is back

### Requirement: Each page's time is logged
A mail pass SHALL log, page by page, how many messages a page listed and the time spent on the network, on the JSON this side reads and writes, in the engine, and in the store's loads and writes; and, for each account's run, its wall time against the network time summed over its mailboxes.

#### Scenario: A first round
- GIVEN a debug build
- WHEN a mailbox's page lands
- THEN the log names its count and the four times

#### Scenario: A pass's gain
- GIVEN a debug build
- WHEN an account's mailboxes have all landed
- THEN the log names how many ran on how many sessions, the wall time and the network time summed

## REMOVED Requirements
