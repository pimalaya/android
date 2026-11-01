---
cairn: log
change: the-shape-follows-storage-draft-08
date: 2026-11-01
---

# The shape follows storage-draft-08: landed

Steps 2a and 2b and the re-pins to io-pimdir 09d2d1c and fd0c060 (cairn/log/2026-11-01-the-shape-follows-storage-draft-08.md), checked on an Android 15 phone pass by pass, landed with cairn/changes/archive/platform-sqlite and cairn/changes/archive/lists-from-the-index. The change is archived at cairn/changes/archive/the-shape-follows-storage-draft-08. cairn/changes/archive/io-pimdir-draft-05, whose rules 2a built, is closed as abandoned, superseded by this change: its reconciliation and its pin did not land (its proposal says why).

Capabilities moved:

- **offline-store** (spec/offline-store.md): MODIFIED "The store's shape follows the canonical schema" (created by the canonical draft-08 schema alone, the statements the app's own, no shape recorded), "A load carries each message's date" (the date, subject and sender), "A sync that went through says nothing" (a parked round's question), "A scoped load reads what it names" (lists bound through `temp.param`); REMOVED "A schema version is migrated, not recreated"; ADDED "A store of another draft is not opened", "The store's writer opens first", "A body is named by its content and checked before use", "A queued action is read strictly", "A binding keeps what it agreed with the item", "Two sources changing one item diverge", "A round finding most of a collection gone asks first", "A narrowed round enumerates its collection", "The store's statements run on Android 8's SQLite". The introduction names the draft-08 schema, the app's statements and the platform's SQLite.
- **conflicts** (spec/conflicts.md): ADDED "An item conflict is settled by a person's choice", "A resolution names the conflicts it settles", "A body a policy did not adopt is kept"; the introduction names the item's own conflict.

The delta was amended at landing so the spec states what landed (delta.md's preamble): "A body a policy did not adopt is kept" carried from io-pimdir-draft-05's delta; "A scoped load reads what it names" added as MODIFIED, the spec still saying `json_each`; `trusted_schema` qualified to SQLite 3.31 and later, and the blob store's exclusive create and copy named for Android below 11, as platform-sqlite step 3 and the re-pin to 09d2d1c built them. "A load carries each message's date" is replaced whole, as the delta states it, its two earlier scenarios going with it.

## Left

- A binding conflict whose item is conflicted over another body (the server changing a card again before the item's conflict was settled) is offered as the binding's, and its form's save is refused, naming the item's body, for good: settling it needs a resolution through a binding in no conflict, or a form showing both diverging bodies, neither of which the app has. Nothing is lost (both bodies stay held, no source pushes over the item), but the person cannot settle it.
- An item conflict whose diverging body nobody has read is neither offered nor fetched: the hydrate (rust/pimdir/queries/app/handles_below_full.sql) asks for a binding's missing `conflict_object` alone. No path to one was found, the one natural path to an item conflict (a source's conflict resolved after another source moved the item, vector 28) being settled by the save itself. A binding conflict whose body is not downloaded yet opens the ordinary page, its save refused until the hydrate lands the body.
- A `prefer-incoming` or `prefer-existing` collection policy and a source's `PreferRemote` loser are not kept as retained items; the app sets neither, so neither arises.
- A phone book's parked round is never offered (accepting it would delete the cards on the server).
- The sync vectors are not run through the Java owner: no harness drives `PimdirStorage` from io-pimdir's vectors/sync/.
- `trusted_schema` (STORAGE §18, a SHOULD) does not exist below SQLite 3.31, so it is not set on Android 8 to 11.
- The exclusive create and copy, where `RENAME_NOREPLACE` is refused or below API 30, is the app's reading of STORAGE §5, which names no no-replace move for that case; a crash during the copy leaves a part under the name, moved into quarantine/ by the next write or read of that body. Gating `renameat2` below API 30 is a precaution (a seccomp kill), not an observed crash.
- `list_address_placements` is not used by Java.
- Not checked on a device: the parked-round dialog, a Gmail or Microsoft download window (the narrowed rounds' enumeration), an event edit, linking and unlinking an item (`add_reference`), a purge with retained items.
- The `automatic index` notices step 2b's device run logged (`mail_summary(attachment)`, `effective(source)`, a `v(collection)` view) were not read for a missing index.
