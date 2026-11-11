---
cairn: change
id: write-against-the-current-revision
status: active
created: 2026-11-11
---

# Calendar and task writes against the revision they read

## Why

Found by the tasks-follow-the-sync investigation (2026-11-11). A task page, a Clarify step or an event view left open while a calendar pass runs keeps the handle and the body it read at load (TaskWrites.java around lines 121, 148 and 167; EventWrites.java around 70, 227 and 239; EventView.java around 542), and no `if_match` reaches the mutate. Two outcomes:

- the pass bound a create to the server's id: the save fails with "unknown handle", as the Tasks list did before it reloaded after a pass;
- the pass brought in a change from the server: the save writes the stale body over it, and nothing refuses it. io-pimdir would answer `Replaced` (mutate.rs around 683 to 692) if the write named the revision it read. That is a silent loss of the server's change, the class the sync rules forbid.

Mail (MailStager) and contacts (PimdirContacts) look the handle up at write time with `PimdirStorage.handleFor`; that cures the first outcome only, and widens the second if done without a revision guard.

## What (to decide)

- Calendar and task writes name the revision they read (`if_match`), so a stale write is refused rather than applied, and the page then offers the item as the pass left it (or the conflict flow), never a silent overwrite.
- They reach the item by its current handle at write time, as mail and contacts do, the revision guard keeping that safe.
- Check whether mail and contacts writes carry the revision too, and close the same gap there if not.
