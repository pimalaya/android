---
cairn: delta
change: platform-sqlite
---

Folds into `spec/offline-store.md`.

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

### Requirement: The store runs on the platform SQLite
The app SHALL open every database it owns, the pimdir store and the index included, on the platform's `android.database.sqlite`, in the app's own shape of the pimdir model (`store_meta.shape` `pimalaya-android`), on every Android it supports (API 26 and up).

#### Scenario: An Android 8 device
- GIVEN a device whose platform SQLite is 3.18
- WHEN the app creates the store and the index
- THEN both are created, and every statement runs, on the platform SQLite

### Requirement: An incompatible store blocks start-up
The app SHALL refuse to start on a store of another shape, or one it cannot open, saying the store's version is not compatible and the app must be reinstalled, and SHALL neither read nor write it.

#### Scenario: A store from an earlier test build
- GIVEN a store written before the app's own shape, with `STRICT` tables
- WHEN the new build starts
- THEN it shows the incompatible-version screen, and the store's files are untouched

## MODIFIED Requirements

## REMOVED Requirements

### Requirement: The store runs on the bundled SQLite
Replaced by "The store runs on the platform SQLite": requery is no longer bundled.
