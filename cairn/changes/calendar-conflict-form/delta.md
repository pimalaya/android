---
cairn: delta
change: calendar-conflict-form
---

Folds into `spec/conflicts.md`.

## ADDED Requirements

### Requirement: Calendar conflicts are settled, never dropped
A calendar entry conflicted on any source SHALL be merged by the sync first, a clean merge resolving it silently; a field both sides changed differently SHALL be offered in a form showing both values, and neither side SHALL be discarded until the user picks.

#### Scenario: Two titles
- GIVEN an entry retitled here and on the server since the last sync
- WHEN the sync runs
- THEN the entry shows as conflicted, and its page offers both titles

#### Scenario: Different fields
- GIVEN an entry whose title changed here and whose location changed on the server
- WHEN the sync runs
- THEN the entry carries both changes, with no question asked

## MODIFIED Requirements

## REMOVED Requirements
