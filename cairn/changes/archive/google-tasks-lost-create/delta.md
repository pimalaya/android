---
cairn: delta
change: google-tasks-lost-create
---

# Delta

Folds into spec/tasks.md.

## ADDED Requirements

### Requirement: A Google task whose create was lost is filed once
On a Google Tasks list, a listed task the store binds to nothing SHALL be named after a pending create of that list reading the same in title, notes, due date and completion, so the create lands on it rather than being pushed again. A task the store holds SHALL never be claimed by a create.

#### Scenario: The answer to a create is lost
- GIVEN a task created on the phone whose insert reached Google but whose answer did not
- WHEN the next sync lists the list
- THEN the task is bound to Google's copy and no second task is created

## MODIFIED Requirements

## REMOVED Requirements
