---
cairn: delta
change: mailbox-refusal-skipped
---

Folds into `spec/mail.md`.

## ADDED Requirements

### Requirement: A step and a pass skip a mailbox the server refuses to open
The bridge SHALL answer a `NO` to an IMAP `EXAMINE` or `SELECT` (RFC 3501 6.3.1, 6.3.2: a mailbox that does not exist or cannot be accessed, whatever its text and whether it carries RFC 5530's `[NONEXISTENT]`) with status 404, as it answers a Graph folder or a Gmail label gone, and SHALL NOT answer a `BAD`, a `BYE`, a closed connection or a refused sign-in so. A mail step SHALL leave such a mailbox out: of an IMAP first step's account floor and listing, of a later step's widening, and of a first step's listings elsewhere, the others stepped as if it were not there, and a step that left one out SHALL NOT report the account held whole. A pass SHALL skip such a mailbox and report nothing for it. When every mailbox a step reads, or every mailbox of a pass, is refused, the first refusal SHALL be reported. The next pass's roster SHALL drop a mailbox the server no longer lists, with its messages.

#### Scenario: A folder deleted between the roster and the step
- GIVEN an IMAP account whose roster names a folder another client deleted since, and Exchange answering its `EXAMINE` `NO ... doesn't exist.`
- WHEN its first step runs
- THEN the account floor is taken across its other mailboxes, they are listed and their bodies read, and the step succeeds

#### Scenario: A label a new session does not see yet
- GIVEN a Gmail IMAP account whose label made seconds ago a fresh session refuses, `NO Unknown Mailbox`
- WHEN *Load more* runs a step
- THEN the other mailboxes widen, and the label is stepped by a later step once the server shows it

#### Scenario: A server refusing everything
- GIVEN an Exchange account whose backend is away, every `EXAMINE` answered `NO`
- WHEN a step runs
- THEN the step fails with the refusal, and no frontier moves

## MODIFIED Requirements

### Requirement: An account's mailboxes sync side by side
A mail pass and the background fill SHALL run an account's mailboxes concurrently on a pool of sessions, four for Graph and JMAP, three for IMAP, two for Gmail, each worker on a session of its own that no other worker uses while it runs, the workers taking the mailboxes in the pass's order. A step, the first sync's included, SHALL run on the account's one session. Every storage load, lookup and write SHALL be answered by one writer at a time, so only the network overlaps. A mailbox that fails SHALL leave the others running, the pass reporting the first failure, but for a mailbox the server refuses to open (A step and a pass skip a mailbox the server refuses to open).

#### Scenario: A pass over twelve mailboxes on Graph
- GIVEN a Graph account of twelve mailboxes
- WHEN a pass runs
- THEN four mailboxes are listed at once, the inbox begun first

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

## REMOVED Requirements
