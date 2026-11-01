---
cairn: delta
change: platform-sqlite
---

Folds into `spec/offline-store.md`, and into `spec/mail.md` where marked (step 4, the search index).

## ADDED Requirements

### Requirement: The store's SQL holds to the oldest platform SQLite
Every statement and migration the app runs SHALL prepare on SQLite 3.18 built as Android 8 builds it, with no JSON and no FTS5.

#### Scenario: A newer syntax slips in
- GIVEN a statement using `RETURNING`
- WHEN the unit tests run
- THEN the floor test fails, naming the statement

### Requirement: The index shares the store's SQLite
The app SHALL write and query index.db on the same SQLite as the store, and SHALL link no SQLite into its native library.

#### Scenario: A search during a sync
- GIVEN a sync writing the store and the index refreshing after it
- WHEN the user searches
- THEN one SQLite in the process holds both files, and the search answers once the refresh's current chunk commits

### Requirement: The index is the app's own shape over FTS4
The app SHALL keep a search index, index.db beside the store, in a shape of its own named `pimalaya-android` in `index_meta.shape` (pimdir SEARCH §2): the canonical index on SQLite 3.18, ordinary FTS4 tables in place of FTS5 over text io-pimdir folded, `unicode61` with `remove_diacritics=0` and prefixes of 2 and 3. It SHALL refuse and leave as it is an index of another shape or a later version, and rebuild one of its shape at an earlier version, under another tokenizer or schema, or built from another store. The index SHALL be refreshed by io-pimdir's refresh at start, after each engine write batch and at the end of each sync, a run of at most 200 rows of the store's feed per transaction, each run a deferred transaction that takes no lock of the store.

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

### Requirement: The mail search reads the index (spec/mail.md)
The mail list's search SHALL read its words as a query of pimdir SEARCH §8, answered by the index over every field of every stored message, the body's text included, ordered by date or by name (no relevance order), with the sender and subject match it had before kept beside the index's hits; a query the language refuses SHALL say why on the search field, the sender and subject match still answering.

#### Scenario: A word of a body
- GIVEN a stored message whose body alone holds `roadmap`
- WHEN the user searches `roadmap`
- THEN the message is listed

#### Scenario: A refused query
- GIVEN a message whose subject is `Re: weekly`
- WHEN the user searches `re: weekly`
- THEN the search field says the field `re` is not one of the language's, and the message is listed by its subject

### Requirement: The store runs on the platform SQLite
The app SHALL open every database it owns, the pimdir store and the index included, on the platform's `android.database.sqlite`, the store's statements in the app's own shape (`pimalaya-android`) over the canonical schema, on every Android it supports (API 26 and up).

#### Scenario: An Android 8 device
- GIVEN a device whose platform SQLite is 3.18
- WHEN the app creates the store and the index
- THEN both are created, and every statement runs, on the platform SQLite

### Requirement: The store's writer syncs every commit
The store's writing connection SHALL run with `synchronous = FULL` (STORAGE §4.1) once write-ahead logging is on, whatever the platform's default under logging, SHALL set `trusted_schema` off where the platform's SQLite has it (3.31 and later), and the store SHALL log the SQLite version it runs on as it opens.

#### Scenario: A build whose default is NORMAL
- GIVEN a device whose platform syncs write-ahead logs NORMAL by default
- WHEN the app opens its store
- THEN the writer syncs at every commit, and the log names the SQLite version

### Requirement: An incompatible store blocks start-up
The app SHALL refuse to start on a store of another draft or another schema, or one it cannot open, saying the store's version is not compatible and the app must be reinstalled, and SHALL neither read nor write it.

#### Scenario: A store from an earlier test build
- GIVEN a store written before the app's own shape, with `STRICT` tables
- WHEN the new build starts
- THEN it shows the incompatible-version screen, and the store's files are untouched

## MODIFIED Requirements

### Requirement: The mail list narrows what it shows (spec/mail.md)
The mail list SHALL list every stored message of every mailbox the filter lets through, and SHALL offer a search over every field of the message, its body included, through the search index, the sender and subject match kept beside it, the role chips, an unread chip and an attachments chip, all answered by the store and its index over every stored message. None of these SHALL sync by itself: opening a mailbox, or turning a chip on, shows what the store holds. A pull while a role chip is on SHALL sync that role's mailboxes of the shown accounts alone; otherwise what syncs is what the filter ticks.

#### Scenario: Unread only
- GIVEN read and unread mail in two accounts
- WHEN the unread chip is on
- THEN the list shows both accounts' unread messages and nothing else

#### Scenario: The trash opened
- GIVEN a Gmail account whose trash was never listed
- WHEN the Trash chip is chosen
- THEN the older messages are listed again, a chunk at a time

## REMOVED Requirements

### Requirement: The store runs on the bundled SQLite
Replaced by "The store runs on the platform SQLite": requery is no longer bundled.
