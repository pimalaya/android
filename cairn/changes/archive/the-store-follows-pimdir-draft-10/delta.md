---
cairn: delta
change: the-store-follows-pimdir-draft-10
---

Folds into spec/offline-store.md, but for "A send that may have gone waits for the user", which folds into spec/mail.md, and "A removal names the conflicts it settles", which folds into spec/conflicts.md. The draft names of the capability's opening paragraphs and of "The store's shape follows the canonical schema" and "The store's statements run on Android 8's SQLite" move to draft-10 with it.

## ADDED Requirements

### Requirement: A renamed statement parameter fails where it is bound
Binding a statement by name SHALL refuse, before anything is run, a call that names a value no statement run under that name takes, neither as a parameter nor as a list slot, while leaving one of its parameters unnamed: what a parameter renamed under its caller looks like, which SQLite would run on NULL. Either alone SHALL bind as before, a parameter the caller does not name binding NULL and one map of values serving several statements.

#### Scenario: A parameter the standard renamed
- GIVEN a caller naming `until` for `release_before`, whose parameter is `before`
- WHEN the statement is bound
- THEN the call is refused naming `until`, and nothing is released

#### Scenario: An optional parameter left out
- GIVEN a caller of `release_before` naming its collections and no bound
- WHEN the statement is bound
- THEN it binds, the bound NULL

### Requirement: A message shares its public id only with a row that agrees
A message filed under a stated key SHALL take the public id a row of its kind already holds under that key only when that row's summary states the same subject, date and sender (STORAGE §9, §9.1, `seq_for_link_any`), a create made from another message being read by the summary it is given; a row stating another, or holding no summary, SHALL leave it to draw an id of its own. A card, an event, a task or a file SHALL share as before.

#### Scenario: A Message-ID reused by another message
- GIVEN a message stored in one mailbox, and another message of the same `Message-ID` and another subject listed in a second mailbox
- WHEN the second is written
- THEN it draws a public id of its own

#### Scenario: One message in two mailboxes
- GIVEN a message stored in the inbox
- WHEN the same message, of the same subject, date and sender, is listed in the archive
- THEN both rows hold one public id

### Requirement: A member naming another message is keyed apart
A mail upsert stating a key on a handle its source does not bind SHALL be filed under a key minted over that handle (`dup:<key>#<handle>`, minted again over a key a row or an upsert of the batch holds) when the row the collection holds under the stated key, live, a tombstone or retained, is one the source does not bind whose summary states another subject, date or sender, or, retained, another size where both state one (STORAGE §9, §14; SYNC §6). It SHALL NOT join, land nor revive that row. A create staged under the stated key's provisional handle SHALL move to the minted key's. A row holding no summary, and an upsert stating none, say nothing against.

#### Scenario: A trashed Message-ID reused
- GIVEN a message deleted from a mailbox, its row retained with its body
- WHEN the mailbox's listing brings another message under the same `Message-ID`, with another subject
- THEN the new message is a row of its own, holding no body yet, and the retained row keeps its body and its subject

### Requirement: An item retired while held elsewhere is purged
An item a batch's delete left bound by no source SHALL be purged, its pins released, when the batch ends with it still retained and its account holding the same key live in another collection: with the retiring body, or, the row holding none, at all, a message only in a row whose summary agrees with its own (STORAGE §11.1, `held_elsewhere`). Any other retired item SHALL stay retained. The purge SHALL be settled once the whole batch applied, so an upsert of the batch reviving the row keeps it.

#### Scenario: A message moved on the server
- GIVEN a message stored with its body in the inbox and in the archive
- WHEN the inbox's listing no longer names it
- THEN the inbox row is gone, Deleted items does not list it, and the archive row keeps the body

#### Scenario: A message deleted on the server
- GIVEN a message stored in the inbox alone
- WHEN the inbox's listing no longer names it
- THEN its row is retained, and Deleted items lists it

### Requirement: A lookup answers every holder with its summary
A lookup of link ids SHALL answer, for each, every row of the account holding a body under it that checks against its name, each with the body's hash and size and the subject, date and sender its mail summary states, none of the three for a row holding no mail summary; the app SHALL NOT choose among them, the engine linking a body only from a row agreeing with the message (SYNC §6). A message's lookup SHALL answer no holder under a derived key.

#### Scenario: A reused Message-ID
- GIVEN an account holding a body under a key, its summary stating one subject
- WHEN a message of that key and another subject is upgraded
- THEN the lookup answers the holder with its subject, and the body is fetched rather than linked

### Requirement: A flag change states the set it was made from
A marker toggled on a message, and a delete marking it `\Deleted`, SHALL be staged with the set the store held when it was read (`base`), so the engine applies the change alone to the set held when it is staged (SYNC §7). A message whose markers nobody read has no base: its staged set SHALL replace the set whole. A sent copy taking its provider's markers SHALL replace the set whole.

#### Scenario: A marker landed between the read and the tap
- GIVEN a message read as unflagged and unread, which a sync then marks read
- WHEN the star tapped on the earlier reading is staged
- THEN the message is flagged and still read

### Requirement: A killed writer's temporaries are unlinked
Once per process, off the main thread, the app SHALL unlink every period-prefixed regular file of objects/ and of its shard directories, following no link, but those a writer of the process is still to move, and SHALL flush each directory it unlinked in (STORAGE §5, §11.5). A write creating a shard directory SHALL flush objects/; a write keeping a name it found held SHALL flush the shard directory before the row naming the body commits; the collector SHALL flush the shard directories it unlinked in before it answers.

#### Scenario: A writer killed inside its copy
- GIVEN a temporary a killed process left in a shard directory
- WHEN the app starts
- THEN the file is unlinked, and a temporary a download is writing meanwhile is left

### Requirement: A removal names the conflicts it settles
A removal SHALL be staged as the engine's `Remove` stating the body it was decided over where the page read one (`if_match`), refused, staging nothing, once another write replaced it, and, from the conflict form alone, the diverging bodies the form showed (`resolves`). A removal meeting a conflict, the item's or its binding's, whose diverging body it does not name SHALL be refused, staging nothing (SYNC §7): a delete from a list or an ordinary page settles no conflict. A tombstone SHALL clear an item's conflict only when its removal named it (the engine's `Settle`).

#### Scenario: Deleting from the conflict form
- GIVEN a calendar entry in conflict, open in its conflict form
- WHEN it is deleted there
- THEN the removal names the server version the form showed, the conflict is settled and the delete is pushed

#### Scenario: Deleting a conflicted contact from the list
- GIVEN a contact the phone and the server changed, its conflict not settled
- WHEN it is deleted from the contacts list
- THEN the delete is refused, nothing is staged, and the contact stays conflicted

## MODIFIED Requirements

### Requirement: A store of another draft is not opened
The app SHALL create its store at storage-draft-10 (`store_meta.format_version`, a `store_id` and an `incarnation` by the canonical text, `derivation` 3) and record `sync_version`, sync-draft-10, with the first engine write. It SHALL decide whether an existing store opens by io-pimdir's rules (STORAGE §6) over facts read off the file read-only, and SHALL NOT read, write nor reconcile a store another draft, another program or another sync version wrote: start-up stands aside on a screen saying the app has to be reinstalled, and the background job, the sync adapters and the time-zone receiver do nothing. The app states its conformance as `storage-draft-10 owner (own statement text), sync-draft-10 engine` (STORAGE §17).

#### Scenario: A store an earlier version wrote
- GIVEN a store created before draft-10, a draft-09 store among them
- WHEN the app starts
- THEN it opens on the reinstall screen, and nothing in the store is written

#### Scenario: Sync state an earlier sync draft wrote
- GIVEN a store at storage-draft-10 whose `sync_version` is sync-draft-09
- WHEN the app starts
- THEN it opens on the reinstall screen, and nothing in the store is written

### Requirement: A queued action is read strictly
Every row the app appends to the queue SHALL match its payload schema (STORAGE §15.3, payloads/), checked before anything is written. A message to send SHALL be queued as Annex B.2's `submit`: `source`, `from`, `rcpts` and `subject` alone, the message the row's body; it SHALL be anchored on the collection its sent copy belongs to, the account's sent mailbox, or on the account's outbox when the account marks none; what the outbox lists (the `Message-ID`, the subject and the date) SHALL be derived from the message, never added to the payload. The drain SHALL judge each row's payload as io-pimdir judges it, valid, malformed or unknown (`payload_outcome`), before handing anything over: a malformed row, one of a known action stating no integer `v` among them, is parked whole, naming what is malformed, and an unknown one, another action or another integer `v` of a known one, is skipped, read no further, left pending and untouched. A submission SHALL go out with the envelope its payload states. A park whose reason begins with the reserved prefix `unconfirmed: ` SHALL be recorded behind `failed: ` (STORAGE §15.2).

#### Scenario: A message queued from an account with a sent mailbox
- GIVEN an account whose roster marks a sent mailbox
- WHEN a message is sent from it
- THEN its row is anchored on that mailbox, the outbox lists it, and the next mail pass of that account hands it over

#### Scenario: A sent mailbox the server drops while a message waits
- GIVEN a message waiting, anchored on the sent mailbox
- WHEN a roster no longer lists that mailbox
- THEN the mailbox stays until the message is settled, and the roster's other changes are written

#### Scenario: A row of the earlier payload
- GIVEN a queued submission carrying `messageId` and `sortKey` and no `source` nor `rcpts`
- WHEN the drain runs
- THEN the row is parked, listed as refused with the member it lacks, and the rows behind it go out

#### Scenario: A later producer's row
- GIVEN a queued row stating `v: 2`
- WHEN the drain runs
- THEN it stays pending, its attempts and error untouched

### Requirement: The index is the app's own shape over FTS4
The app SHALL keep a search index, index.db beside the store, in a shape of its own named `pimalaya-android` in `index_meta.shape` (pimdir SEARCH §2), at search-draft-10: the canonical index on SQLite 3.18, ordinary FTS4 tables in place of FTS5 over text io-pimdir folded, `unicode61` with `remove_diacritics=0` and prefixes of 2 and 3. It SHALL record in `index_meta.extraction` the revision of SEARCH §6 io-pimdir extracts under and in `index_meta.extractor` its own revision then io-pimdir's. It SHALL refuse and leave as it is an index of another shape or a later version, and rebuild one of its shape at an earlier version, under another tokenizer or schema, recording another extraction or extractor, or built from another store. The index SHALL be refreshed by io-pimdir's refresh at start, after each engine write batch and at the end of each sync, a run of at most 200 rows of the store's feed per transaction, each run a deferred transaction that takes no lock of the store.

#### Scenario: A sync writing while the index refreshes
- GIVEN a refresh run reading the store
- WHEN a sync writes a page to the store
- THEN the write goes through, the run holding no lock on the store

#### Scenario: A fresh install
- GIVEN no store on the device
- WHEN the app first starts and the index refreshes
- THEN the store is created before the index reads it, and index.db holds its `index_meta` after that first start

#### Scenario: An index another program built
- GIVEN an index.db whose `index_meta.shape` is `canonical`
- WHEN the app opens its index
- THEN it neither reads, writes nor deletes it, and the mail search answers from the sender and subject alone

#### Scenario: An index extracted by an earlier build
- GIVEN an index of this shape whose `index_meta.extractor` names another revision
- WHEN the app opens its index
- THEN the index is rebuilt, every body extracted again

### Requirement: A search's days are the index's zone's
A date term of a search (pimdir SEARCH §8.1) SHALL read a day, a month, a year, `today` and `yesterday` from midnight in the zone `index_meta.floating_zone` states, the phone's zone the refresh wrote, a day a transition moves lasting 23 or 25 hours; a relative amount (`1d`, `2w`) SHALL stay the instant that long before now. A bound no instant of a four-digit year states SHALL be open: a start before `0000-01-01T00:00:00Z`, an end past `9999-12-31T23:59:59Z`. An index stating no zone SHALL read them in UTC, and one stating a zone the time zone database does not know SHALL refuse the query.

#### Scenario: Today turns over at the phone's midnight
- GIVEN an index stating `Europe/Paris` and an event at 00:30 on 1 January 2027 there (23:30Z the day before)
- WHEN `date:today` is searched at 2026-12-31T23:30:00Z
- THEN the event is found, 1 January having started in Paris; at 22:30Z it is not

#### Scenario: A range to the year 9999
- GIVEN mail dated in 2026
- WHEN `date:2026..9999` is searched
- THEN it answers what `date:2026..` answers

### Requirement: A send that may have gone waits for the user
A submission that fails after the whole message was handed over and before any verdict came back SHALL be uncertain: over SMTP once the data's final `.` was written, over JMAP, Graph or Gmail once the request was written whole and no byte of its answer came, or when a 500, 502, 503 or 504 answered. An uncertain submission SHALL park its row under an error beginning with the reserved prefix `unconfirmed: ` (pimdir STORAGE §15.2), shown as *May have been sent*, and SHALL never be handed over again on its own. The reader SHALL offer *Send again* on it, which puts the same row back among the pending ones (`retry_action`, STORAGE §15.5), its id and its attempts kept, in its place in the queue's order.

#### Scenario: The connection dies after the final dot
- GIVEN an SMTP server that closes the connection after the message's final `.`
- WHEN the sync drains the queue
- THEN the row is parked, reads *May have been sent*, and the next sync hands nothing over

#### Scenario: The app stops while sending
- GIVEN a message the drain is handing over
- WHEN the app is killed before the server's answer is recorded
- THEN the next sync parks it as *May have been sent* and sends the messages behind it

#### Scenario: The user sends it again
- GIVEN a message parked as *May have been sent* that the recipient never got
- WHEN the user opens it and taps *Send again*
- THEN its row is pending again, before a message queued after it, and the next sync hands it over

## REMOVED Requirements

None.
