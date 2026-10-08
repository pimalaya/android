---
cairn: delta
change: mail-offline-policy
---

## ADDED Requirements

### Requirement: An account chooses which bodies it keeps offline
A mail account's settings SHALL offer three offline policies: bodies on open (the default), where a body is fetched when its message is opened; bodies in the background; and whole mailbox, which sets the account's bound to all mail and holds it there, the bound's choice dimmed, and downloads bodies in the background. The settings SHALL also offer a switch, off by default, to download bodies on a metered network. Both SHALL be committed when picked. The bound SHALL be the only limit on what is kept: there is no storage cap, space being freed from Deleted items and by narrowing the bound.

#### Scenario: The default
- GIVEN a mail account just added
- WHEN a pass lists its mailboxes
- THEN no body is downloaded until a message is opened

#### Scenario: Whole mailbox
- GIVEN an account bounded to the last 6 months
- WHEN its offline policy is set to whole mailbox
- THEN its bound reads all mail and cannot be changed, the fill lists below the old floor, and every listed body is downloaded

### Requirement: A mailbox can be downloaded whole
A mailbox's row on the mail filter page SHALL offer "Download", which after a confirmation keeps that mailbox whole whatever its account's policy: its scope SHALL be all of its mail, past the account's bound, the fill SHALL widen it below its floor, narrowing the account's bound SHALL NOT collect it, and every listed body SHALL be downloaded. The row SHALL then say it is kept whole and offer "Stop", which returns it to the account's bound and policy, what is stored staying until the bound is narrowed again.

#### Scenario: An archive kept whole
- GIVEN an account bounded to the last year, with bodies on open
- WHEN its Archive is downloaded from the filter page
- THEN every message of the Archive is listed and its body stored, and the account's other mailboxes keep their year and fetch bodies on open

### Requirement: Bodies download behind the list
For each account that is on, and whose policy downloads bodies or holds a mailbox kept whole, the app SHALL raise the listed messages lacking a body to `Full` through the engine's upgrade, after each pass and each fill step while the app is open: newest first, the mailboxes the mail filter shows before the others, at most 24 bodies an account a step, side by side on the account's pool. A message dated below its mailbox's bound SHALL NOT be downloaded, nor an undated one where a bound applies, nor a pending create. A body the store already holds under the same link id SHALL be linked rather than fetched. The body SHALL be stored as the bytes the server sent, its base moved to it, its summary and sort key kept, its attachment mark restated from its parts. The step SHALL run only while the app is in the foreground, no other sync runs, and a network is there that is unmetered unless the account allows a metered one; it SHALL never be periodic work, and SHALL stop when the user starts a sync or leaves the app. A body the server fails to hand over SHALL be left below `Full` and tried by a later run; an account whose download fails as a whole (no session, an expired token) SHALL leave the run. Gmail body reads SHALL count against the account's per-minute quota units like any other read, with no pacing of their own.

#### Scenario: Within the bound only
- GIVEN an account bounded to the last year with bodies in the background, holding a message from yesterday and one from three years back
- WHEN the body step runs
- THEN yesterday's body is downloaded and stored, the other stays a summary

#### Scenario: A metered network
- GIVEN an account with bodies in the background on mobile data
- WHEN a pass ends
- THEN no body is downloaded until an unmetered network is back, or the account allows metered networks

#### Scenario: The same message under two labels
- GIVEN a Gmail message under two labels, its body downloaded in one
- WHEN the body step reaches the other
- THEN the body is linked from the store and not read again

### Requirement: The drawer shows bodies left to download
While the body step runs for an account, the account's pill in the drawer SHALL say how many bodies it has left to download, in place of when it last synced, and SHALL go back to that once the step ends.

#### Scenario: A first whole download
- GIVEN an account set to whole mailbox
- WHEN the drawer is opened while its bodies download
- THEN its pill counts the messages left, falling as they land

## MODIFIED Requirements

### Requirement: An opened message is stored and read back
The app SHALL store an opened message as its item's object, as the bytes the server sent, and SHALL render a later open from the store without reaching the network, whether the body was stored by an open or by the body step. Fetching SHALL happen only for a message the store does not hold.

#### Scenario: The same message twice
- GIVEN a message opened once
- WHEN it is opened again
- THEN it renders from the store, with no request

#### Scenario: A message never opened
- GIVEN a message the store holds at meta
- WHEN it is opened
- THEN the message is fetched, stored, and rendered from what was stored

#### Scenario: Offline
- GIVEN a stored message and no network
- WHEN it is opened
- THEN it renders

#### Scenario: Downloaded in the background
- GIVEN a message whose body the body step stored
- WHEN it is opened with no network
- THEN it renders

### Requirement: An account bounds its mail
An account's settings SHALL offer to sync all of its mail or the last 1, 3, 6, 12 or 24 months, the floor being the first day of the month that many months back, on the `Date` header (a message with no usable date in every scope). A mailbox's floor SHALL never go below its bound: its account's, or none for a mailbox kept whole or an account set to whole mailbox. A chunk reaching past the bound stops at it, and a mailbox whose floor is the bound is whole. A provider's received-date filter SHALL only narrow a listing, two days below the floor (IMAP `SENTSINCE` one day below). Widening the bound SHALL have the fill carry on below the old floor, chunk by chunk. Narrowing it SHALL collect the stored messages dated below the new floor that owe nothing to the server, their mailboxes keeping them there, except in a mailbox kept whole.

#### Scenario: Narrowing to a year
- GIVEN an account syncing all of its mail
- WHEN its bound is set to the last year
- THEN the messages older than that leave the store, except one with a change not pushed yet or one in a mailbox kept whole
- AND nothing is deleted on the server

#### Scenario: Widening again
- GIVEN that account
- WHEN its bound is set back to all mail and the app stays open on an unmetered network
- THEN the older messages are listed again, a chunk at a time

## REMOVED Requirements
