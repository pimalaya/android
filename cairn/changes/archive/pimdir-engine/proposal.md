---
cairn: change
id: pimdir-engine
status: landed
created: 2026-09-08
---

# Take the engine from io-pimdir, and the typed summaries with it

## Why

io-replica is retired. The offline engine moved into io-pimdir as the sync half of the pimdir standard, every `Replica*` type is now `Pimdir*`, and the crate is the only place the app can take it from. rust/Cargo.toml already asks for io-pimdir 0.5 alone, so the bridge does not compile.

The move is not a rename. io-pimdir 0.5 folds STORAGE Annex A into the schema: the opaque `items.meta` column is gone, and what a reader lists an item from is a typed row in its kind's summary table with the people it names in `item_address`. The engine carries that summary instead of a meta string, a fetch answers one, and an edit restates one. So the app's own summary convention (PimdirMeta, `{"v": 1, ...}`) is superseded by the standard's, and every read that parsed it and every write that produced it moves onto the tables.

Three smaller edges come with the version: a drop carries a third reason (`rekeyed`, a rebuilt handle space, which the store must not read as a removal), a lookup answers the body's size beside its hash, and the canonical SQL is indexed as `sql::all()` with the migrations beside it rather than `sql::ALL` plus a reshaped-index list.

## What

Port the JNI bridge onto io-pimdir's coroutines, and the store onto the 0.5 schema.

The wire between them changes in one place that matters: a placement, a fetched item and an `edit` mutation carry a typed `summary` where they carried a `meta` string. It is externally tagged by kind (`{"contact": {...}}`), its fields are the summary table's columns, and it carries the addresses Annex A derives, each with its role and position, so the store writes `item_address` verbatim rather than deriving roles of its own.

Java writes the summary tables through the canonical statements rather than column lists of its own, which needs one thing PimdirSql lacks: a binder rewriting the `:name` parameters SQLite on Android does not take into the positional ones it does. Splitting the schema needs one thing too, now that it declares triggers: a body's own semicolons end nothing.

The derivations are io-pimdir's wherever a body is on hand, as they are in neverest: `indexCard` returns the standard's contact summary and sort key beside the app's index, so a card is derived once, by the format's own rules. The one derivation the app still writes itself is a message's, which is built from an IMAP envelope with no body to read.

What Annex A has no column for is not stored:

- a contact's phone, its fallback info line and its normalised content hash are derived at read time from the body the contacts list already loads, rather than kept in a table of the app's own. The list reads one blob per row either way, and a second copy of derived data is a second thing to keep true.
- a calendar entry's ETag, which was in the meta for want of anywhere else, becomes what it always was: the `base_revision` of the `server` binding the mirror now writes.

## Deliberately not in scope

The engine still syncs contacts only. Mail and calendar stay read-only mirrors written outside it, and this change moves their rows onto the summary tables without giving them bindings beyond the one the calendar ETag needs.
