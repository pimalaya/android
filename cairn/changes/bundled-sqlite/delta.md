---
cairn: delta
change: bundled-sqlite
---

## ADDED Requirements

### Requirement: The store runs on the bundled SQLite
The app SHALL open every database it owns, the pimdir store included, on the SQLite it bundles, version 3.37 or newer with JSON1, on every Android it supports (API 26 and up), and SHALL NOT open one on the platform's `android.database.sqlite`, whose version follows the device.

#### Scenario: An Android 8 device
- GIVEN a device whose platform SQLite is 3.18
- WHEN the app creates the store
- THEN the `STRICT` tables are created and the `RETURNING` and `json_each` statements run, on the bundled SQLite

## MODIFIED Requirements

### Requirement: The store's shape follows the canonical schema
The store SHALL reconcile its own shape against the canonical pimdir DDL on open: creating every table, index and trigger the schema declares that it lacks, adding every column it lacks, dropping every column the schema no longer declares, and rebuilding an index or a trigger whose text has moved. It SHALL NOT carry a transcribed list of the columns a draft folded in or out, nor of the indexes one reshaped.

#### Scenario: A column the draft folded in
- GIVEN a store created before `bindings.conflict_object` was declared
- WHEN the app opens it
- THEN the column is added and the store opens

#### Scenario: A column the draft folded out
- GIVEN a store carrying `bindings.ambiguous_handles`, which the schema no longer declares
- WHEN the app opens it
- THEN the column is dropped, or kept with a log where SQLite refuses to drop it

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

## REMOVED Requirements
