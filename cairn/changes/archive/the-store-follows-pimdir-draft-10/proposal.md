---
cairn: change
id: the-store-follows-pimdir-draft-10
status: landed
created: 2026-11-13
---

# The store follows pimdir draft-10

## Why

pimdir tagged storage-draft-10, sync-draft-10 and search-draft-10 (ae8b231), the three drafts its audit of 2026-11-12 produced, and io-pimdir 0.11.0 follows them. Drafts are exact (STORAGE §4.2): the app opens the draft its build names and no other, so it either follows or stays on a draft nothing else speaks. The app is the store's second owner: the engine is io-pimdir's, but the store is opened and written in Java over the platform SQLite, so most of the follow is the app's own, which no compiler sees.

Measured on 2026-11-13 against io-pimdir 49e1fd5: 6 compile errors in the bridge, then 28 of 654 Rust tests failing, and in Java five statement calls binding a renamed parameter. `PimdirSql.bind` binds NULL for a parameter its caller does not name, so each rename would have failed silently: `release_before` under a NULL bound releases every body of the account's mailboxes, `purge_retained_before` purges nothing.

## What

- **The pin**: io-pimdir 0.11 from crates.io, no git patch.
- **The statements** (STORAGE §4.4): the forks of `evict_before` (was `collect_before`), `release_before`, `release_bases_before`, `purge_retained_before`, `bump_next_seq` and `init_index_meta` read again from the canonical text, a fork of the new `retry_action`, the column `mail_in_reply_to.message_id` in the app's own reads. The Java callers bind `:before` and `:message_id`.
- **A guard on the binding**: a statement call naming a value its statement does not take while leaving one of its parameters unnamed is refused, so a renamed parameter fails where it is bound. Measured over the 1,004 JVM tests: 42 call shapes leave a parameter unnamed and 10 name a value their statement does not take, each on purpose, and none does both, so refusing either alone would break the app and refusing both breaks nothing.
- **Identity** (STORAGE §9, §9.1, §11.1, §14; SYNC §6): a message shares its public id only with a row agreeing with its summary on subject, date and sender; a lookup answers every holder of a key with those three, the engine linking a body only from one that agrees; a member naming a row that holds another message is keyed apart; an item retired while its account holds it elsewhere is purged, a move losing nothing.
- **Mutations** (SYNC §7): a removal states the body it was decided over and the conflicts it resolves, a flag change the set it was changed from.
- **The queue** (STORAGE §15.2 to §15.5): the reserved prefix `unconfirmed: ` for a submission whose outcome is unknown, *Send again* through `retry_action`, a payload judged valid, malformed or unknown by io-pimdir.
- **The index** (SEARCH §2, §8.1): `index_meta.extraction` and `extractor`, an index recording another of either rebuilt, the index `object_not_ok`, a date bound no four-digit year states read as open.
- **The blob store** (STORAGE §5): objects/ flushed when a write creates a shard, the shard flushed when a held name is kept and after the collector's unlinks, the temporaries a killed writer left unlinked.
- **The conformance statement**: `storage-draft-10 owner (own statement text), sync-draft-10 engine`.

## Not in this change

The JVM runner of the sync vectors, the blob verification on open, the paged re-derivation, the write as a coroutine of the core, the `synchronous` read-back (ledger lines A2, A3, A5, A6, A7 of pimdir's audit), the owner and staging locks and the receipt on acknowledge (A4), a coverage line under a search, an in-app erase of a refused store, and any screen for the engine's `Disagreed`, `Abandoned` and waiting reports.

## What a user meets

A store an earlier build wrote is refused, as every draft's is: the app opens on its reinstall screen, and what was not synced from that store is lost.
