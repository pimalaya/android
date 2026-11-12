---
cairn: change
id: guarded-contact-updates
status: active
created: 2026-11-11
---

# Contact updates guarded on JMAP and Microsoft Graph

## Why

contact-moves-carrying-edits (2026-11-11) made a Google contact update guarded: a stale etag re-reads the person and merges against the base, a field both sides changed comes back as a conflict, and the unguarded retry is CardDAV's alone. JMAP and Microsoft Graph updates send no guard: they patch the changed properties, so a property edited on the server between the listing and the push is overwritten by the phone's value, unseen. That is last-writer-wins on a genuine collision, which the sync rules forbid (cairn/spec/conflicts.md).

## What (to decide)

- Graph: send `If-Match` with the contact's `@odata.etag` (io-msgraph needs to carry it), a 412 handled as Google's is.
- JMAP: `ContactCard/set` with `ifInState` guards the whole account's contacts, so a busy account would refuse often; read back the changed cards (`ContactCard/changes` since the listing's state) before the update instead, and treat a card changed since as a conflict for the next listing's merge.
- Tests failing first: a property edited on the server between the listing and the push is never written over, on each backend.
- Contacts' editor writes (ContactWriter.saveFanOut and saveMerge, DuplicateReview.link) stage over the body and handle read when the editor opened, with no `ifMatch`: apply write-against-the-current-revision's guard and recovery (BodyReplaced, a three-way merge through mergeConflictForm, the conflict form on a collision).
- Also left by contact-moves-carrying-edits: record a refused Google update's conflict by relisting the refused handle in the same pass (the engine then records the observed revision and body) instead of mark_conflicted; stop a refused move with no listed change repeating every pass; report an add carried by a book that is never synced; drop the ghost membership card_awaited keeps after "Drop it".
