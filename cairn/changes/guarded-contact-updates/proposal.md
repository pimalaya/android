---
cairn: change
id: guarded-contact-updates
status: active
created: 2026-11-11
---

# Contact updates guarded on JMAP and Microsoft Graph

## Why

contact-moves-carrying-edits (2026-11-11) made a Google contact update guarded: a stale etag re-reads the person and merges against the base, a field both sides changed comes back as a conflict, and the unguarded retry is CardDAV's alone. JMAP and Microsoft Graph updates send no guard: they patch the changed properties, so a property edited on the server between the listing and the push is overwritten by the phone's value, unseen. That is last-writer-wins on a genuine collision, which the sync rules forbid (cairn/spec/conflicts.md).

## What (decided by the owner, 2026-11-19)

- JMAP, landed first: `ContactCard/changes` since the listing's state read just before `ContactCard/set`, a card changed since treated as a conflict for the next listing's merge. The set carries `ifInState` set to the state that `/changes` request just returned (owner ruling 2026-11-19), never the listing's, which guards the whole account over the listing-to-push gap and would refuse often on a busy one: the fresh state closes the one-round-trip window between the two requests, and a `stateMismatch` leaves the update pending for the next pass's merge (rejected, not refused), as a detected change does.
- Graph (owner decision 2026-11-19, replacing the If-Match route: docs/providers.md records Graph contacts as taking no If-Match on updates): mirror the JMAP route, just before the PATCH read the contact's `changeKey` and compare it with the one the listing saw; a mismatch is not written over and becomes a conflict for the next listing's merge. App-only: no io-msgraph release, no live check.
- Tests failing first: a property edited on the server between the listing and the push is never written over, on each backend.
- Contacts' editor writes (ContactWriter.saveFanOut and saveMerge, DuplicateReview.link) stage over the body and handle read when the editor opened, with no `ifMatch`: apply write-against-the-current-revision's guard and recovery (BodyReplaced, a three-way merge through mergeConflictForm, the conflict form on a collision).
- The Google leftovers of contact-moves-carrying-edits moved to their own change, google-contact-refusals.
