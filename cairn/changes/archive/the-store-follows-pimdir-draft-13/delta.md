---
cairn: delta
change: the-store-follows-pimdir-draft-13
---

Folds into spec/offline-store.md, spec/mail.md and spec/carddav-sync.md.

## ADDED Requirements

### Requirement: A change refused as it stands is held
A change a source's server refuses as it stands (pimdir SYNC §4) SHALL be answered to the engine `Refused` with the server's text, which the engine records on the binding the change was pushed for and holds while it derives the same change, under the same key, reporting it every run (SYNC §5); any other failure SHALL stay a rejection, pushed again by the next run. The store SHALL record a refusal by the canonical `refuse_binding` and clear one by `clear_refusal`, after the batch's upserts and stamps, the reason scrubbed of credentials (STORAGE §18) before it crosses to Java, and SHALL hand the source's refusals on every load (`list_refusals`). A sync report SHALL carry each refusal once with its reason, and the app SHALL log it; no screen shows it yet.

#### Scenario: A change the server will never take
- GIVEN a staged change whose push the server refuses as it stands
- WHEN a sync pushes it
- THEN the refusal is recorded on its binding and logged, and the next sync pushes it not again while it is the same change, logging it again

#### Scenario: The change moves on
- GIVEN a refusal standing on a message's binding
- WHEN the user stages another change of it and its push is accepted
- THEN the refusal is cleared

### Requirement: A mail push refused as it stands is held
The mail connectors SHALL tell a write the server refuses as it stands from one that may land later (pimdir SYNC §4), as neverest tells them: Graph, Gmail and a JMAP request by a status naming the request (400 to 499 but 401, 407, 408, 409, 412, 423, 425 and 429) that names no quota nor rate limit (Graph's `ErrorQuotaExceeded`, Gmail's `quotaExceeded` and `...LimitExceeded` reasons or `RESOURCE_EXHAUSTED`, a text naming a quota or a rate limit); a JMAP `Email/set` by its `notUpdated` or `notDestroyed` type, refused for `tooManyKeywords`, `tooManyMailboxes`, `blobNotFound`, `invalidPatch`, `invalidProperties`, `singleton`, `forbidden` and `tooLarge`, never for `notFound`, `willDestroy`, `overQuota`, `rateLimit` or a type io-jmap does not name; IMAP by a `NO` naming `CANNOT`, `PARSE`, `TOOBIG` or `NOPERM`, never a `BAD`, a `NO` naming no code or a state that passes (`OVERQUOTA`, `INUSE`, `UNAVAILABLE`). A marker, a delete, a move, a copy and an append so refused SHALL be answered refused (A change refused as it stands is held) after a 404 is taken as a message gone (A push converges on a message gone); every other failure SHALL fail the push as before.

#### Scenario: A marker the server cannot store
- GIVEN an IMAP server answering `NO [CANNOT]` to the `UID STORE` of a staged marker
- WHEN the sync pushes it
- THEN the mailbox's sync goes on, the change is held and logged, and later syncs leave it unpushed

#### Scenario: A full mailbox
- GIVEN an IMAP server answering `NO [OVERQUOTA]` to an append
- WHEN the sync pushes it
- THEN the push fails as before, and a later sync pushes it again

### Requirement: A JMAP answer holding an object it could not read is not whole
An `Email/query`, an `Email/get` and a `Mailbox/get` whose answer holds an object io-jmap could not deserialize (io-jmap 0.10's `unparsed`) SHALL fail with an error naming the method and how many objects went unread, where leaving it out would read as a message or a mailbox gone, as the whole call failed before io-jmap 0.10.

#### Scenario: A mailbox of a shape the app cannot read
- GIVEN a JMAP account whose `Mailbox/get` lists a mailbox whose name is no string
- WHEN the roster is read
- THEN it fails, and no mailbox is dropped for being absent from it

## MODIFIED Requirements

### Requirement: The store's shape follows the canonical schema
Was draft-11: the store SHALL be created by the canonical draft-13 schema alone.

### Requirement: A store of another draft is not opened
Was storage-draft-11 and sync-draft-11: the app SHALL create its store at storage-draft-13 and record `sync_version` sync-draft-13; a store created before draft-13, a draft-11 store among them, and sync state sync-draft-11 wrote are refused, the app opening on its reinstall screen. The conformance statement reads `storage-draft-13 owner (own statement text), sync-draft-13 engine`.

### Requirement: The index is the app's own shape over FTS4
Was search-draft-11: the index is kept at search-draft-13, an index of an earlier draft rebuilt.

### Requirement: A narrowed round enumerates its collection
The io-pimdir 0.12 shortfall sentence goes: io-pimdir 0.14 (sync-draft-13) finds a narrowed round's member absent only when its enumeration lacks it, whatever its date, and none when it hands none. A scenario is added: a Microsoft 365 message whose `Date` lies in the scope and whose `sentDateTime` does not is kept.

### Requirement: The sync vectors run through the app's load and write
At io-pimdir 0.14, format 7: the counts and the conformance statement's draft move, the runner seeds and compares a binding's `refusal` and `refused_key` and feeds a `Refused` outcome back with its reason.

### Requirement: A message's markers can be written
The scenario "The server refuses" is restated: a write refused as it stands is held (A mail push refused as it stands is held); any other failure is reported and the marker stays staged for the sync that follows.

### Requirement: An IMAP listing that skipped a response is not whole
A FETCH response io-imap 0.11 decodes in part counts as a response unread, and the messages it read in part are left out of the answer, as io-imap 0.9 skipped the whole line: a message lacking an item asked (its flags, its header, its size) would otherwise read as one stating none.

### Requirement: A DAV listing that is not whole is refused
A CardDAV card listing naming a card without its data (a 403 or 404 row, io-webdav 0.9's `unreadable`) SHALL fail too; a multiget's unreadable member is left out and logged, as one the server did not answer.

## REMOVED Requirements

None.
