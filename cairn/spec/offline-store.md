---
cairn: spec
capability: offline-store
status: current
---

# Offline store

The app keeps one pimdir store for every account and every domain: `collections.kind` carries the media type (`message/rfc822`, `text/vcard`, `text/calendar`) and `collections.account` groups by account, so the merged view's two filter axes are columns rather than three databases. Bodies live in a content-addressed blob directory beside the database, referenced by hash and refcounted.

The schema is io-pimdir's, handed to Java over JNI rather than transcribed, and executed against Android's own SQLite. The crate is taken without its `client` feature: the platform ships SQLite, and compiling a second engine into every ABI would work against the app's first design goal.

A contacts collection is reconciled by io-pimdir's sync engine against two sources, the server and the phone, which the store holds as one item with one `bindings` row per source. Mail and calendar collections are read-only mirrors: they carry no binding beyond the one a calendar's ETag lives in, no staged edit and no conflict, and a refresh replaces their contents.

### Requirement: The engine is io-pimdir's
The bridge SHALL run io-pimdir's sync, upgrade and mutate coroutines, and the app SHALL NOT depend on io-replica.

#### Scenario: A sync pass
- GIVEN an address book with a staged edit
- WHEN the app syncs it
- THEN `PimdirSync` derives the push and the driver services its yields

### Requirement: The store's shape follows the canonical schema
The store SHALL reconcile its own shape against the canonical pimdir DDL on open: creating every table, index and trigger the schema declares that it lacks, adding every column it lacks, dropping every column the schema no longer declares, and rebuilding an index or a trigger whose text has moved. It SHALL NOT carry a transcribed list of the columns a draft folded in or out, nor of the indexes one reshaped.

#### Scenario: A column the draft folded in
- GIVEN a store created before `bindings.conflict_object` was declared
- WHEN the app opens it
- THEN the column is added and the store opens

#### Scenario: A column the draft folded out
- GIVEN a store carrying `bindings.ambiguous_handles`, which the schema no longer declares
- WHEN the app opens it
- THEN the column is dropped, or kept with a log where the platform's SQLite cannot drop one

#### Scenario: A table the draft added
- GIVEN a store created before the summary tables were declared
- WHEN the app opens it
- THEN they are created and the store opens

#### Scenario: An index whose columns moved
- GIVEN a store holding an index of the canonical name over other columns
- WHEN the app opens it
- THEN the index is dropped and recreated from the canonical DDL

#### Scenario: A shape the parse could not read
- GIVEN a table the canonical DDL yielded no column for
- WHEN the app reconciles
- THEN it changes nothing about that table, rather than reading the empty answer as "every column is stale"

### Requirement: An item carries the standard's summary
The store SHALL record what a reader lists an item from in its kind's summary table and the people it names in `item_address`, as pimdir STORAGE Annex A defines them, written through the canonical statements. It SHALL NOT keep a summary convention of its own in the item row, and SHALL derive one through io-pimdir wherever the body is on hand.

#### Scenario: A card is written
- GIVEN a vCard with a `FN`, a `UID` and two `EMAIL` properties
- WHEN the app writes it
- THEN `contact_summary` holds its name, uid, kind and org, and `item_address` holds both emails under the `email` role in document order

#### Scenario: A message listed but not fetched
- GIVEN an IMAP envelope and no body
- WHEN the mail mirror writes the row
- THEN `mail_summary` holds the same fields the body's derivation would, built from the envelope

#### Scenario: A summary the writer did not restate
- GIVEN an item whose summary is stored
- WHEN a write carries none
- THEN the stored summary is kept, on the same terms as the sort key

### Requirement: An unnamed handle is a probe
A placement carrying no link id SHALL be recorded as a probe of its source, never as an item keyed by its handle, and SHALL be loaded back as a probed placement. A named placement SHALL forget the probe its handle held, and a handle bound to another link id SHALL have that binding retired first: a handle names one item per source.

#### Scenario: An enumeration reports a member
- GIVEN a collection whose enumeration yields handles and no identities
- WHEN the store writes them
- THEN each is a probe, no item is keyed by a handle, and the next load offers them as probed placements

#### Scenario: A fetch names a probed handle
- GIVEN a probed handle
- WHEN the upgrade resolves its identity and upserts the placement
- THEN the probe is forgotten and the handle is bound exactly once

#### Scenario: A resource replaced in place
- GIVEN a handle bound to one link id
- WHEN an upsert of the same handle carries another
- THEN the binding it held is retired and the handle binds the new identity alone

### Requirement: A placement no source binds is offered under its provisional handle
The store SHALL hand out `U+0001` followed by the link id as the handle of a placement the source does not bind (SYNC §2), and SHALL resolve that handle back to the identity it spells. A push SHALL name the resource by that identity, never by the provisional handle.

#### Scenario: A card staged for a book the phone has never seen
- GIVEN an item with no binding for the phone spoke
- WHEN that spoke loads the collection
- THEN the placement carries the provisional handle, and a row read under it resolves to the item

#### Scenario: The create is pushed
- GIVEN a staged create under its provisional handle
- WHEN the push offers it to the server
- THEN the resource is named after the card, and the binding takes whatever handle the push assigns

### Requirement: One identity under two handles
A write resolving an existing `(collection, link_id, source)` binding to a different handle SHALL be refused, unless the same batch supersedes the handle it holds. The store SHALL NOT record the incoming handle in the bound one's place, and SHALL NOT freeze the item.

#### Scenario: A source reports one identity twice
- GIVEN a binding bound to `v1.vcf`
- WHEN an upsert of the same link id arrives under `v2.vcf` with nothing superseding `v1.vcf`
- THEN the write is refused and the binding keeps `v1.vcf`

#### Scenario: A handle-space rebuild
- GIVEN a batch dropping `v1.vcf` as superseded
- WHEN the same batch upserts the link id under `v2.vcf`
- THEN the binding is replaced and the item stays

### Requirement: A rebuilt handle is not a removal
The store SHALL read a `rekeyed` drop as this row going and the item staying, on the same terms as a `superseded` one.

#### Scenario: A rebuilt spine
- GIVEN a collection whose handle space the remote renumbered
- WHEN the engine drops every old handle as `rekeyed` and upserts the new ones
- THEN no item is retired and no delete propagates to another source
