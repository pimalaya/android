---
cairn: log
change: store-migrations-vendor-vcard
landed: 2026-10-06
---

# The store migrates, and the provider vCard mappings come from the vendor crates

**Migrations.** `PimdirDb.onUpgrade` dropped every table and recreated the store, which was right while it was a sync cache and is not now that it holds staged writes and the outbox. It now runs every canonical migration above the store's version (`PimdirSql.migrations()`), stamping `store_meta.version` as io-pimdir's runner does, and `onCreate` is the same run from zero. Only `onDowngrade` still recreates. The pimdir STORAGE draft has a single migration today, so nothing runs it yet: it is ready for the first one after the freeze.

**vCard mappings.** rust/src/google.rs and rust/src/msgraph.rs were the app's copies of the People and Graph contact projections, from before io-gpeople and io-msgraph grew a `vcard` feature. Both are gone, and rust/src/client/google.rs and graph.rs call `GpeoplePerson::to_vcard`, `from_vcard`, `changed_fields`, `id` and `MsgraphContact::to_vcard`, `from_vcard`, `create_from_vcard`, `update_from_vcard` directly, with the crates' `GPEOPLE_PERSON_VCARD_FIELDS`, `GPEOPLE_PERSON_STASH_KEY` and `MSGRAPH_CONTACT_STASH_EXPAND`. The three project.rs helpers only they used went with them. Two behaviours move with the crates:

- A card's UID is the one the stash carries, minted from the provider id only for a contact the provider created itself, where the app minted it from the id every time.
- The stash is named `cardamum.vcard` (People `clientData`) and `cardamum-vcard` (Graph extended property), where the app wrote `pimalaya.*`. A contact stashed by an earlier build reads back without its stashed lines once; the next write stashes them under the shared name, so Cardamum and the app round-trip each other's contacts.

Only the no-base update mask (`MANAGED_FIELDS`) stays app-side, in client/google.rs, the crate exposing none.

Capabilities moved: offline-store.
