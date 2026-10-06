---
cairn: delta
id: incremental-enumeration
---

# Delta

## ADDED Requirements

### Requirement: A pass asks what changed
Every domain SHALL enumerate a collection from the cursor its last pass stored, and SHALL report the round as incomplete so nothing it did not mention is retired. A collection with no cursor, or one whose cursor the server rejects, SHALL be enumerated whole.

#### Scenario: A calendar nothing touched
- GIVEN a calendar synced once
- WHEN it is synced again with nothing changed
- THEN one REPORT carries the answer and no event body is read

#### Scenario: A mailbox nothing touched
- GIVEN a mailbox synced once against a QRESYNC server
- WHEN it is synced again with nothing changed
- THEN the select carries the answer and no envelope is fetched

#### Scenario: A cursor the server rejects
- GIVEN a stored sync token the server no longer accepts
- WHEN the collection is enumerated
- THEN the round falls back to a complete one and stores a fresh cursor

### Requirement: A body is read only when the merge asks for it
A calendar's bodies SHALL be fetched by `calendar-multiget` for the handles the fetch yield names, and a message's envelope by a UID fetch for the handles it names. Neither SHALL be read for a member the merge did not ask about.

#### Scenario: One event changed
- GIVEN a calendar of five hundred events, one of them edited remotely
- WHEN it is synced
- THEN one body is read

#### Scenario: One message arrived
- GIVEN a mailbox whose only change is one new message
- WHEN it is synced
- THEN one envelope is fetched

## MODIFIED Requirements

### Requirement: A mail account is walked once per pass
A pass SHALL list the account's mailboxes once, then reconcile each of them on the session it opened. It SHALL NOT pre-fetch a window of every mailbox before reconciling any.

#### Scenario: A pass over an account
- GIVEN an account of fifteen mailboxes
- WHEN it is synced
- THEN one LIST names them and each is enumerated in turn on the one session

#### Scenario: The window on a full round
- GIVEN a mailbox with no usable cursor
- WHEN it is enumerated
- THEN the newest messages of it are taken, not all of them, the store holding a window rather than a mailbox
