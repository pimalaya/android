---
cairn: delta
change: deleted-items
---

## ADDED Requirements

### Requirement: Deleted items can be restored or purged
Settings SHALL list every item the store retains after its deletion, across mail, contacts and calendars, newest deletion first, each with its summary, domain, account, last collection and deletion time. An item whose body the store holds SHALL be restorable into a collection of its kind the user picks, its last one preselected, as a local creation the next sync uploads. An item held as summary only SHALL say it is not stored on the device. An item a source still binds SHALL show as waiting for the server, with no action. The page SHALL show the space a purge releases and purge every retained item, then collect unreferenced bodies, on confirmation.

#### Scenario: A contact deleted by mistake
- GIVEN a contact deleted and synced
- WHEN it is restored from Deleted items into its address book
- THEN it shows in the address book at once, and the next sync uploads it

#### Scenario: A mail never opened
- GIVEN a deleted mail whose body was never fetched
- WHEN Deleted items lists it
- THEN it shows, says it is not stored on the device, and offers no restore

## MODIFIED Requirements

## REMOVED Requirements
