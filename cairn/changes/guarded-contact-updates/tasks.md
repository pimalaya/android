---
cairn: tasks
change: guarded-contact-updates
---

# Tasks

- [x] Owner reviews the proposal (2026-11-19: JMAP first, Graph after its library release, leftovers split out)
- [x] JMAP: failing test first (a property edited on the server between the listing and the push is never written over), then `ContactCard/changes` before `ContactCard/set` (2026-11-19: read in its own request right before the set, a JMAP request being unable to hold a set back on an earlier call's result)
- [x] JMAP: the set carries `ifInState` set to the state the `/changes` request returned, a `stateMismatch` answered rejected (owner ruling 2026-11-19); failing test first (`an_update_the_account_moved_past_before_the_set_is_not_written`)
- [x] Editor writes (ContactWriter.saveFanOut and saveMerge, DuplicateReview.link): failing test first, then write-against-the-current-revision's guard and recovery
- [x] Graph: changeKey read before the PATCH, a mismatch a conflict; failing test first
- [ ] Spec folded, log written, change archived
