---
cairn: delta
change: store-performance
---

Folds into `spec/offline-store.md` (the first three ADDED), `spec/mail.md` (the fourth ADDED and the MODIFIED) and `spec/calendar.md` (the last ADDED).

## ADDED Requirements

### Requirement: A scoped load reads what it names
A load scoped to some handles or link ids SHALL read those rows alone, whatever the length of the list: the list SHALL be bound as one value (a JSON array read through `json_each`), never widened back to the whole collection, and a handle scope SHALL seek the source's bindings by handle rather than walk the collection. An unbound row SHALL still be reached under the provisional handle of its link id.

#### Scenario: A listing page of a thousand
- GIVEN a mailbox of 275k messages
- WHEN a round's page of 1000 members is loaded by handle
- THEN 1000 placements come back, read by key, and the page costs what it holds rather than the mailbox

### Requirement: A store write is never read through a cursor
A write SHALL run as a statement and count what it changed (`changes()`), its `RETURNING` clause dropped, never stepped through a cursor: a cursor runs its statement again each time it refills its window, so a delete read through one runs again past its first window. A purge whose pins a statement would return SHALL leave them to the collector's recount.

#### Scenario: Narrowing a large account
- GIVEN 120k messages below a new bound
- WHEN the bound is narrowed
- THEN all 120k are collected once and counted, in one pass

### Requirement: A deep read seeks where it lands
A keyset page, the frontier's dates and the body plan SHALL state the bound their cursor implies on the sort key, so the index serves them from the cursor down rather than from the newest row. A count with no attachment chip SHALL NOT read the summary. A page reached with no page above it read (a fling) SHALL read from the start of the day its first row falls on, by the per-day counts the list already holds, paying for that day's rows ahead of it and no more.

#### Scenario: A fling to the end of a million messages
- GIVEN a merged list of 1M stored messages
- WHEN it is flung to its last page
- THEN that page reads from its own day, in milliseconds

### Requirement: A step asks the store which of a page it holds
A mail step SHALL NOT carry the handles the store holds: each backend SHALL ask the store which of a page it lists the store holds, a page at a time, through the driver it was handed. Whether a mailbox holds anything SHALL be one indexed probe.

#### Scenario: A Gmail step over a large account
- GIVEN a Gmail account of 500k stored messages
- WHEN Load more runs a step
- THEN each listed page of 500 ids is asked of the store, and no list of 500k handles crosses the bridge

### Requirement: The agenda builds off the main thread
The agenda SHALL read and expand its entries on a thread of its own and draw the result on the main thread, the last reload asked winning. An entry's expansion SHALL be kept while the shown week and its object stay the same.

#### Scenario: Coming back to the app
- GIVEN 10k stored entries
- WHEN the app returns to the foreground
- THEN the screen stays responsive while the agenda rebuilds, and only entries a sync changed are expanded again

## MODIFIED Requirements

### Requirement: A message body is read through the bridge
The bridge SHALL read the RFC 5322 source of messages into files (`fetchMessageSources`), and SHALL turn a stored source into what the reader draws with no network access (`parseMessage`, `parseMessageFile` from the stored file). A JMAP account SHALL read that source by downloading the message's blob (RFC 8620 section 6.2), an IMAP one by fetching it with `BODY.PEEK[]`, streamed. A Graph account SHALL read it as the MIME Graph serves at `messages/{id}/$value`, a Gmail one as the `raw` format of the message. No message SHALL cross the bridge as a string.

#### Scenario: A JMAP message
- GIVEN a JMAP account
- WHEN a message is opened
- THEN its `blobId` is read and the blob downloaded
- AND the reader renders the same shape it renders an IMAP message from

#### Scenario: A message of 100 MB
- GIVEN an IMAP message of 100 MB not on the phone
- WHEN it is opened
- THEN it is streamed into a file, filed in the store and rendered from it

### Requirement: Bodies cross the bridge as files
A message read whole for the store SHALL cross the bridge as a file: `fetchMessageSources(handle, collection, ids, dir)` SHALL write one file per message into `dir` and answer each one's id, path and size (with its labels and reception on Gmail), a message gone or not handed over left out, an expired token failing the call. It SHALL dispatch per backend, a backend without batches of its own reading one message after another. JMAP SHALL read the `blobId`s of every id in one `Email/get` (chunked by `maxObjectsInGet`) and download the blobs one after another, never one `Email/get` per message. On IMAP it SHALL read the sizes (`UID FETCH <set> (UID RFC822.SIZE)`), then one streamed `UID FETCH <set> (UID BODY.PEEK[])` per batch of at most 50 messages and about 12 MB, a message past it alone, each body written to its file as it arrives; a batch the server refuses SHALL be read one message after another. The bridge SHALL describe the file in one read (`describeMessage`: its content hash, its summary, its attachments), and the file SHALL be moved into the blob store under that hash, never read on the Java side nor copied, and handed to the engine as a body already persisted. Opening one message SHALL go the same way, and an attachment SHALL be written from the stored file to its own file.

#### Scenario: A batch of bodies
- GIVEN ten Gmail messages to download
- WHEN the body step reads them
- THEN one batch request answers them, each written to its own file, and no body crosses as a string

#### Scenario: An IMAP batch
- GIVEN 40 IMAP messages of an inbox to download
- WHEN the body step reads them
- THEN one `UID FETCH` of their sizes and one streamed `UID FETCH` of their bodies answer them, each written to its own file

#### Scenario: JMAP bodies
- GIVEN ten JMAP messages to download
- WHEN the body step reads them
- THEN one `Email/get` names their blobs, and each blob is downloaded into its own file

#### Scenario: Opening a message not on the phone
- GIVEN a message the store holds at meta
- WHEN it is opened
- THEN it is read into a file, moved into the blob store under the hash the bridge computed, and parsed from there

## REMOVED Requirements
