---
cairn: delta
change: follows-storage-draft-17
---

# Delta: the app follows storage-draft-17

Capability files: cairn/spec/offline-store.md (all entries below), cairn/spec/mail.md (one). The capability introduction of offline-store.md (line 11) follows too: "storage-draft-15" becomes "storage-draft-17", and "taken without its `client` feature" becomes "taken with `indexer` and without `client` or `search`".

## ADDED Requirements

### Requirement: A relocation stating its member's handle lands its create
When an accepted relocating remove states the handle its member took in the destination (`PimdirPushResult::assigned`: an IMAP `COPYUID`, a Graph move's new id), the store SHALL apply the engine's `LandPlacement` in the batch recording the outcome. The provisional create is dropped `Superseded`, and its binding moves to the stated handle, based on the item's flags, body and revision. When the source already binds that handle in the collection, the store SHALL record the create delivered instead (pimdir SYNC §4).

#### Scenario: A message moved on IMAP
- GIVEN a message moved into Archive, the server answering `COPYUID` with UID 812
- WHEN the push's outcome is written
- THEN Archive's create is bound under 812 at once, and a later listing of another message sharing its `Message-ID` lands nothing on it (SyncVectorsTest, the `LandPlacement` cases)

### Requirement: A binding keeps the server's object id
A mail binding SHALL record the id its server gives the message's content, as the source last stated it with the member's meta (`bindings.server_object_id`): Gmail's message id, JMAP's `Email/id`, IMAP's `EMAILID` where the server announces `OBJECTID`, Graph's message id. A load SHALL hand it back. Two bindings of one source stating different ids SHALL never share a body by link id (pimdir SYNC §6).

#### Scenario: Two messages sharing a Message-ID on Gmail
- GIVEN two Gmail messages with one `Message-ID`, the same subject, date, sender and size, one of them held in full
- WHEN the other is upgraded
- THEN its body is fetched, never linked from the first

### Requirement: A settled collection is not synced by the background job
A background pass SHALL leave unsynced a collection that the store calls settled for the source (`settled_collections`) while the source states no change since that checkpoint, asked over the same coverage with no full listing. An open app's pass, a pull and a widening SHALL sync every collection they take, as before.

#### Scenario: An idle phone
- GIVEN an account of 40 mailboxes synced, nothing staged, and no change on the server
- WHEN the background job runs
- THEN it asks each mailbox's state, runs the engine on none, and writes nothing to the store

## MODIFIED Requirements

### Requirement: The store's shape follows the canonical schema
The store SHALL be created by the canonical storage-draft-17 schema alone (0001_init.sql and `init_store_meta`), served over JNI, and SHALL NOT reconcile, widen, narrow nor rebuild a store of another shape. Drafts are exact (STORAGE §4.2), and a store whose schema differs from the canonical text is refused as damaged. The statements it runs SHALL be the app's own shape, `pimalaya-android` (STORAGE §4.4: each canonical statement's name, parameters, columns and effect kept, its text free). A statement of the app's own SHALL name a body by its hash and join `objects` by id, never read or write an object pointer column (`items.object`, `conflict_object`, `bindings.base_object`, `conflict_object`, `shared_object`, `queue.object`) as a hash. The store SHALL record no shape: a store the canonical statement text wrote is the same store and opens.

### Requirement: A flag set's system flags are the row's bits
A placement's or a base's flag set SHALL be written whole, in the row's own write: its system flags (`\Seen`, `\Answered`, `\Flagged`, `\Draft`, `\Deleted`, each by its exact bytes) as `marks` and `base_marks`, and every other flag as `items.keywords` and `bindings.base_keywords`, sorted bytewise and joined by one space, `\seen` included (STORAGE §13). The bridge SHALL compute both (`codec::keywords_to_text`). An unknown set SHALL write `0` and `''`. Every read SHALL answer the bits and the keywords as one set. No Java reads which flag is which, and no Java builds the keywords text.

### Requirement: A store of another draft is not opened
The app SHALL create its store at storage-draft-17 (`store_meta.format_version`, a `store_id` and an `incarnation` by the canonical text, `derivation` 3) and record `sync_version`, sync-draft-15, with the first engine write. It SHALL decide whether an existing store opens by io-pimdir's rules (STORAGE §6) over facts read off the file read-only, and SHALL NOT read, write nor reconcile a store another draft, another program or another sync version wrote. Instead, start-up stands aside on a screen saying the app has to be reinstalled, and the background job, the sync adapters and the time-zone receiver do nothing. A refusal for the store's `format_version` SHALL say which of the two is the earlier, by STORAGE §4.2's order, or that the order reads neither. The app states its conformance as `storage-draft-17 owner (own statement text), sync-draft-15 engine` (STORAGE §17).

#### Scenario: A store the previous release wrote
- GIVEN a store at storage-draft-15, which every build on io-pimdir 0.19 wrote
- WHEN the app starts
- THEN it opens on the reinstall screen, its reason `store-version (storage-draft-15, earlier than this app's storage-draft-17)`, and nothing in the store is written

#### Scenario: A store a later version wrote
- GIVEN a store at storage-draft-18 or at a released storage version
- WHEN the app starts
- THEN it opens on the reinstall screen, its reason naming the store's version later than the app's, and nothing in the store is written

### Requirement: The index is the app's own shape over FTS4
The app SHALL keep a search index, index.db beside the store, in a shape of its own named `pimalaya-android` in `index_meta.shape` (pimdir SEARCH §2), at search-draft-17. It is the canonical index on SQLite 3.18: ordinary FTS4 tables in place of FTS5 over text io-pimdir folded, `unicode61` with `remove_diacritics=0` and prefixes of 2 and 3, a placement naming its body by `object.id`, and no `chunk`, `thread` or `split` table. A snippet SHALL be cut from the body, and a thread's span SHALL be read from the store. The flag table SHALL hold keywords alone; `is:` and a `tag:` naming a system flag SHALL read `items.marks` as the store holds it. The app SHALL serve the index statements its bridge binds and no others. It SHALL record in `index_meta.extraction` the revision of SEARCH §6 io-pimdir extracts under, and in `index_meta.extractor` its own revision, then io-pimdir's. It SHALL refuse and leave as it is an index of another shape or a later version. It SHALL rebuild one of its shape at an earlier version, under another tokenizer or schema, recording another extraction or extractor, or built from another store. The index SHALL be refreshed by io-pimdir's refresh at start, after each engine write batch and at the end of each sync. A run folds at most 200 rows of the store's feed per transaction, each run a deferred transaction that takes no lock of the store.

### Requirement: The sync vectors run through the app's load and write
The requirement's text is unchanged but for its last two sentences, which become: "At io-pimdir 7f16b27, 200 cases (format 11): N pass, none fails, M are unbound. While a case is listed `fails`, the app's `sync-draft-15 engine` statement stands short of SYNC §12 by it." N and M are the counts step F1 measures, M at most 39.

### Requirement: The mail search reads the index (mail.md)
The mail list's search SHALL read its words as a query of pimdir SEARCH §8 at search-draft-17, answered by the index over every field of every stored message, the body's text included. It is ordered newest first or by title (io-pimdir's `NewestFirst` and `Title`; no oldest-first, sender or relevance order), with the sender and subject match it had before kept beside the index's hits. `mid:` SHALL match a stored `Message-ID` whole. `label:` and `property:` SHALL read the store's item properties, which the app writes none of. A query the language refuses, a reserved form (`~`, `/`, `{`) among them, SHALL say why on the search field, the sender and subject match still answering.

## REMOVED Requirements

None.
