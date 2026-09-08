---
cairn: delta
change: mail-body-cache
---

## ADDED Requirements

### Requirement: An opened message is stored and read back
The app SHALL store an opened message as its item's object, as the bytes the server sent, and SHALL render a later open from the store without reaching the network. Fetching SHALL happen only for a message the store does not hold.

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

### Requirement: A mirror refresh keeps the bodies it does not restate
A read-only mirror write carrying no body SHALL keep the object the item already holds, and an item's level SHALL follow what it holds: meta with no body, full with one.

#### Scenario: A sync after a read
- GIVEN a message whose body was stored by opening it
- WHEN the mailbox is refreshed
- THEN the stored body survives the refresh

## MODIFIED Requirements

### Requirement: A message body is read through the bridge
The bridge SHALL answer the RFC 5322 source of one message (`fetchMessageSource`), and SHALL turn a source into what the reader draws with no network access (`parseMessage`). A JMAP account SHALL read that source by downloading the message's blob (RFC 8620 section 6.2), an IMAP one by fetching it whole with `BODY.PEEK[]`.

#### Scenario: A JMAP message
- GIVEN a JMAP account
- WHEN a message is opened
- THEN its `blobId` is read and the blob downloaded
- AND the reader renders the same shape it renders an IMAP message from
