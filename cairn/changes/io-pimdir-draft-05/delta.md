---
cairn: delta
change: io-pimdir-draft-05
---

Folds into `spec/offline-store.md` (the store's shape and its open) and `spec/mail.md` (flag pushes). Draft until the port's shape is reviewed.

## ADDED Requirements

### Requirement: The agreed body stays on the phone
The store SHALL pin the body a binding last agreed on with its source, as it pins the base, so the common ancestor a merge reads is never collected while a binding names it.

#### Scenario: A conflict settled later
- GIVEN a contact both sides edited, its agreed body no other item names
- WHEN the store collects garbage before the conflict is settled
- THEN the agreed body is still held, and the merge reads it

### Requirement: A body a policy did not adopt is kept
An edit settling a conflict by a rule SHALL keep each diverging body it does not adopt as a retained item, and SHALL be refused while the conflict holds a body nobody has read.

#### Scenario: Keep the phone's
- GIVEN a calendar entry in conflict whose server body was never opened
- WHEN the conflict is settled by a rule keeping the phone's body
- THEN the write is refused until the server's body is read, and once read it is kept as a retained item

### Requirement: A flag change pushes what changed
A flag change SHALL be pushed as the flags added and the flags removed, so a flag another client changed meanwhile survives the push.

#### Scenario: Read here, flagged elsewhere
- GIVEN a message marked read on the phone and flagged on the web before the next sync
- WHEN the sync pushes the read mark
- THEN the server holds the message read and flagged

## MODIFIED Requirements

## REMOVED Requirements
