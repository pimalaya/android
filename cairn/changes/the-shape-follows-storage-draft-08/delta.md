---
cairn: delta
change: the-shape-follows-storage-draft-08
---

Folds into spec/offline-store.md: what steps 2a and 2b change there, but for "An item conflict is settled by a person's choice", which folds into spec/conflicts.md. Not folded yet, the device check coming first.

## ADDED Requirements

### Requirement: A store of another draft is not opened
The app SHALL create its store at storage-draft-08 (`store_meta.format_version`, a `store_id` and an `incarnation` by the canonical text, `derivation` 3) and record `sync_version` with the first engine write. It SHALL decide whether an existing store opens by io-pimdir's rules (STORAGE §6) over facts read off the file read-only, and SHALL NOT read, write nor reconcile a store another draft, another program or another sync version wrote: start-up stands aside on a screen saying the app has to be reinstalled, and the background job, the sync adapters and the time-zone receiver do nothing.

#### Scenario: A store an earlier version wrote
- GIVEN a store created before draft-08
- WHEN the app starts
- THEN it opens on the reinstall screen, and nothing in the store is written

### Requirement: The store's writer opens first
Opening the store SHALL configure its writing connection before write-ahead logging gives the pool its readers, so no reader is opened, nor caches the schema, before the store's tables exist. The writer SHALL erase what it deletes (`secure_delete`) and trust no function the schema names (`trusted_schema` off).

#### Scenario: A first launch
- GIVEN no store on the device
- WHEN the app starts and lists its collections
- THEN the store is created on its writer alone, and no statement fails for a table it lacks

### Requirement: A body is named by its content and checked before use
The blob store SHALL file a body under its BLAKE3 name, one shard level deep (`objects/<hash[0:2]>/<hash>`), checking the name before any path is built, and SHALL write it by renaming a temporary with `RENAME_NOREPLACE`, never by a hard link, which Android's SELinux policy denies an app on its own files: a name already held keeps what holds it. It SHALL follow no link under objects/, and SHALL check a body against its name before serving, parsing, pushing or copying it, moving one that fails into quarantine/.

#### Scenario: A message body downloaded on a device
- GIVEN Android 15 refusing `link` to the app
- WHEN a sync downloads a message
- THEN its body is stored and the message reads offline

#### Scenario: Two writers of one body
- GIVEN a body filed under its name
- WHEN another writer files the same name
- THEN the first file stays and the temporary is deleted

### Requirement: A queued action is read strictly
Every row the app appends to the queue SHALL match its payload schema (STORAGE §15.3, payloads/), checked before anything is written. A message to send SHALL be queued as Annex B.2's `submit`: `source`, `from`, `rcpts` and `subject` alone, the message the row's body; it SHALL be anchored on the collection its sent copy belongs to, the account's sent mailbox, or on the account's outbox when the account marks none; what the outbox lists (the `Message-ID`, the subject and the date) SHALL be derived from the message, never added to the payload. The drain SHALL read each row by its schema before handing anything over: a malformed row is parked whole, naming what is malformed, and a row stating a later payload version is skipped, left pending and untouched. A submission SHALL go out with the envelope its payload states.

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

### Requirement: A binding keeps what it agreed with the item
Every binding SHALL record in `bindings.shared_object` the body it last agreed with the shared item (STORAGE §5, SYNC §9): moved by an upsert carrying a body the item settled on, never by one carrying none nor one leaving the item conflicted, and pinned as the base is (refcount, recount, collector), so it outlives the item's own body.

#### Scenario: A pull at meta
- GIVEN a card whose binding agreed on its body
- WHEN a pull takes the body away
- THEN the binding still names the body and the body stays stored

### Requirement: Two sources changing one item diverge
The store SHALL merge a source's upsert of a mutable kind into the shared item by io-pimdir's cross-source rules (SYNC §9): a change one source made flows in, and two sources having changed the item differently diverge, the item keeping its body, recording the incoming one in `items.conflict_object` and standing conflicted for every source, the write reporting the upsert's handle in `Written.conflicted`. A message never diverges.

#### Scenario: A card edited on the phone and on the server
- GIVEN a card the phone edited and the store took
- WHEN the server's listing brings its own edit of the card
- THEN the card keeps the phone's body, records the server's and reads as conflicted, and nothing is pushed over either

### Requirement: An item conflict is settled by a person's choice
An item two sources diverged on SHALL be offered, once, as the conflicts of its collection's server binding are: merged three ways by the sync pass first, the item's body the local side, its diverging body the remote one, and the body the disagreeing source last agreed with the item (its `shared_object`, else its base) the ancestor, none when no binding names one; a clean merge staged as the resolution, a field both sides set apart left to the resolution form, the contact or the entry carrying the conflicted mark meanwhile. A person's resolution, staged on that binding, SHALL settle the item whatever body it keeps (SYNC §7): the item takes the body, its diverging one released and kept nowhere, and each source owes the body where its base differs. A binding SHALL be conflicted by its own divergence alone, the remote revision it records, never by the status its item's conflict projects.

#### Scenario: Keeping the phone's card
- GIVEN a card the phone and the server changed, the phone's kept and the server's recorded
- WHEN the form is saved keeping every value the phone set
- THEN the card is no longer conflicted, the phone owes nothing and the server is pushed the phone's card

#### Scenario: A server conflict resolved after the phone moved the card
- GIVEN a card whose server conflict waits for the form, and the phone changing the card meanwhile
- WHEN the form is saved over the card the phone moved
- THEN the item's conflict this records merges with nothing to ask and is settled by the same save, and the card does not read as conflicted

### Requirement: A round finding most of a collection gone asks first
A round whose listing names no member while the store binds some, or finds more than half of at least 20 bound members absent (SYNC §5, §8), SHALL park rather than remove them, and the app SHALL say so once the pass ends, naming each collection and how many of its items the server no longer lists, offering to remove them too or to keep them. Removing them SHALL run the next sync of those collections accepting the absences; keeping them SHALL leave the round parked, decided again by the next pass.

#### Scenario: A calendar the server emptied
- GIVEN a calendar of 40 entries the server now lists empty
- WHEN a sync ends
- THEN the 40 entries are still there and a dialog says the server no longer lists them, offering Remove and Keep

### Requirement: A narrowed round enumerates its collection
A mail round whose listing a date filter narrowed SHALL say so on every page, and its last page SHALL hand every handle the mailbox holds, read with no date filter: IMAP `UID SEARCH ALL`, JMAP `Email/query` filtered by the mailbox alone, Graph the folder's message ids, Gmail the label's ids with no query. A member the narrowed listing did not name SHALL be retired only when this enumeration does not name it either.

#### Scenario: An undated message under the download window
- GIVEN a Gmail label holding a message received before the round's `after:`
- WHEN the round's last page lands
- THEN the message stays, the label's ids naming it

### Requirement: The store's statements run on Android 8's SQLite
Every statement the app runs on the store SHALL prepare and run on SQLite 3.18 as Android 8.0 builds it (no `RETURNING`, upsert, `UPDATE ... FROM`, `STRICT` nor JSON), the store tests running on that build. A statement 3.18 lacks a construct for SHALL be the canonical one's effect in several statements run in order under its one name, in one transaction, never one at a time by its caller. No SQL SHALL be written in Java.

#### Scenario: A summary written twice
- GIVEN a message whose summary the store does not hold
- WHEN a listing writes it, then a later one restates it with no invitation
- THEN the row is inserted, then updated, keeping the invitation the first write stated

#### Scenario: A store the previous build wrote
- GIVEN a store created by io-pimdir's canonical statement text at storage-draft-08
- WHEN this build starts
- THEN the store opens, and its rows are read and written by the app's statements

## MODIFIED Requirements

### Requirement: The store's shape follows the canonical schema
The store SHALL be created by the canonical draft-08 schema alone (0001_init.sql and `init_store_meta`), served over JNI, and SHALL NOT reconcile, widen, narrow nor rebuild a store of another shape: drafts are exact (STORAGE §4.2), and a store whose schema differs from the canonical text is refused as damaged. The statements it runs SHALL be the app's own shape, `pimalaya-android` (STORAGE §4.4: each canonical statement's name, parameters, columns and effect kept, its text free), and the store SHALL record no shape: a store the canonical statement text wrote is the same store and opens.

#### Scenario: A store whose index moved
- GIVEN a store holding an index the canonical text does not declare
- WHEN the app starts
- THEN the store is refused, not rebuilt

### Requirement: A load carries each message's date
A store load SHALL carry each mail placement's `Date`, `Subject` and sender beside its state, which the bridge SHALL hand the engine as a summary holding those alone and SHALL NOT write back: a write handing it back unchanged SHALL carry no summary, leaving the stored row alone. The engine reads the date to tell a placement in a round's scope from one outside it (SYNC section 5), and the three to tell a listed member reusing a create's `Message-ID` from the create it lands (SYNC section 6).

#### Scenario: A sent copy listed by the server
- GIVEN a message sent from the app, its copy staged in Sent
- WHEN Sent's listing brings the copy the provider filed
- THEN the staged copy lands on it and is not pushed a second time

### Requirement: A sync that went through says nothing
A sync that went through SHALL NOT pop up a report: no count of what came in or went out, no count of messages sent from the outbox, no count of conflicts left, the lists showing what the pass brought and a conflicted row carrying its own mark. A pull turned down because a background run holds the sync SHALL say nothing either, that run's dock already showing. A failure SHALL still open its error dialog, and a parked round its question (A round finding most of a collection gone asks first).

#### Scenario: A quiet pass
- GIVEN a contacts pass bringing three cards and leaving one conflict
- WHEN it ends
- THEN no toast shows, and the conflicted contact carries its mark in the list

## REMOVED Requirements

### Requirement: A schema version is migrated, not recreated
Removed: while the format is a draft no migration runs on an existing store; a store of another version is refused (A store of another draft is not opened).
