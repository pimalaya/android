---
cairn: delta
id: pimdir-engine
---

# Delta

## ADDED Requirements

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

### Requirement: A rebuilt handle is not a removal
The store SHALL read a `rekeyed` drop as this row going and the item staying, on the same terms as a `superseded` one.

#### Scenario: A rebuilt spine
- GIVEN a collection whose handle space the remote renumbered
- WHEN the engine drops every old handle as `rekeyed` and upserts the new ones
- THEN no item is retired and no delete propagates to another source

## MODIFIED Requirements

### Requirement: The store's shape follows the canonical schema
The store SHALL reconcile its own shape against the canonical pimdir DDL on open: creating every table, index and trigger the schema declares that it lacks, adding every column it lacks, dropping every column the schema no longer declares, and rebuilding an index or a trigger whose text has moved. It SHALL NOT carry a transcribed list of the columns a draft folded in or out, nor of the indexes one reshaped.

#### Scenario: A table the draft added
- GIVEN a store created before the summary tables were declared
- WHEN the app opens it
- THEN they are created and the store opens

#### Scenario: An index whose columns moved
- GIVEN a store holding an index of the canonical name over other columns
- WHEN the app opens it
- THEN the index is dropped and recreated from the canonical DDL

### Requirement: The engine is io-pimdir's
The bridge SHALL run io-pimdir's sync, upgrade and mutate coroutines, and the app SHALL NOT depend on io-replica.

#### Scenario: A sync pass
- GIVEN an address book with a staged edit
- WHEN the app syncs it
- THEN `PimdirSync` derives the push and the driver services its yields

## REMOVED Requirements

### Requirement: An item carries an opaque summary
Superseded by the standard's typed summaries: `items.meta` no longer exists, so neither does the convention that filled it.
