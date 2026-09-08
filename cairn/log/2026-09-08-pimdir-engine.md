---
cairn: log
change: pimdir-engine
landed: 2026-09-08
---

# Take the engine from io-pimdir, and the typed summaries with it

io-replica is retired: the offline engine moved into io-pimdir as the sync half of the pimdir standard, so the bridge now runs `PimdirSync`, `PimdirUpgrade` and `PimdirMutate`, and the app depends on one crate where it depended on two.

The rename was the small half. io-pimdir 0.5 folds STORAGE Annex A into the schema, so `items.meta` is gone and what a reader lists an item from is a typed row in its kind's summary table with the people it names in `item_address`. The engine carries that summary, a fetch answers one and an edit restates one, so the app's own convention (the `{"v": 1, ...}` object PimdirMeta produced) is superseded by the standard's and PimdirMeta is now PimdirSummary, the app's half of Annex A.

On the wire a placement, a fetched item and an `edit` mutation carry a `summary` tagged by kind, whose fields are the summary table's columns and which lists every address with its role and position. Java writes it through the canonical statements, bound by name: PimdirSql gained a binder rewriting `:name` into the positional parameters Android takes, so the column lists stay in the crate. Splitting the schema needed one thing too, the DDL now declaring triggers: a body's own semicolons end nothing.

A load deliberately answers no summary. A write carrying none keeps the stored row, on the same terms as the sort key, and the engine only ever sets one from a derivation, so reading five typed tables back into a placement would buy nothing the engine does anything with.

The derivations are io-pimdir's wherever a body is on hand, as they are in neverest: `indexCard` returns the standard's contact summary and sort key beside the app's index, so a card is derived once by the format's own rules and the fetch path cannot disagree with the edit path. The one summary the app still builds itself is a message's, from an IMAP envelope with no body to read, which Annex A provides for.

Two fields moved rather than being kept. A contact's phone, info line and normalised hash are derived at read time from the body the contacts list already loads, so there is no second copy of derived data to keep true. A calendar entry's ETag, which was in the meta for want of anywhere else, is now the `base_revision` of the `server` binding the mirror writes, which is what it always was.

Three smaller edges came with the version: a drop carries a third reason (`rekeyed`, a rebuilt handle space, which the store reads as this row going and the item staying), a lookup answers the body's size beside its hash, and the canonical SQL is `sql::all()` with the migrations beside it, so the reshaped-index list is gone and the store reconciles its shape against the DDL itself, tables and triggers included.

Capabilities moved: offline-store.

## What the first live run against Fastmail caught

The port landed green on the build and failed on the phone: onboarding pulled four cards, then the hydrate pass died on `UNIQUE constraint failed: bindings.collection, bindings.source, bindings.handle`. 0.5 made `bindings_by_handle` unique, and the seam was writing two bindings for one handle.

The cause was a model the port had carried over rather than moved: a placement with no link id was being filed as an item keyed by its handle. An enumeration yields handles and no identities, so every member landed as an item named after its href; the upgrade then resolved the real identity and upserted the same handle under the vCard UID, and the store dutifully bound the href twice. 0.4's index tolerated that and answered from whichever row it liked.

The store now follows the reference client: an unnamed handle is a probe, in the `probes` table 0.5 added, loaded back as a probed placement so the merge still sees the member; a named placement forgets the probe and, when its handle was bound to another identity, retires that binding first, as a delete of the handle would.

The same reading fixed a second thing the port had missed: 0.5 names a placement no source binds by the provisional handle its key derives, `U+0001` then the link id, where 0.4 used the link id itself. The store hands out that handle, resolves it back wherever a row is read, and both push adapters name the resource after the card rather than after this side's bookkeeping, so a create no longer offers a server a name beginning with a control character.

## And what the second run caught

The address book synced and stayed empty: `pulled: 4`, then nothing. A probe holds no item row, and the pass that raises placements to their body found them by joining `items`, so the four members the enumeration reported were never fetched. The hydrate pass now asks for the source's probes too, which is the definition of below full, and the quiet path counts them as work rather than skipping a pass over them.

The reconcile also had its order backwards: it created what a store lacked before widening what it held, so an index reading a column the schema had added since was refused on a column that was not there yet, and the app died on open against a store from an earlier draft. Tables and their columns first, then the indexes and triggers that read them.
