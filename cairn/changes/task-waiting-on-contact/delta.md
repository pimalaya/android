---
cairn: delta
change: task-waiting-on-contact
---

Folds into `spec/tasks.md`.

## ADDED Requirements

### Requirement: Who a task waits on is a contact
The Waiting on row SHALL pick a contact, writing its name and the address picked as `CONTACT` with `ALTREP="mailto:..."`, free text staying possible. A task whose `CONTACT` names an address SHALL reference every contact holding it, and its row SHALL open that contact; the contact's page SHALL list the tasks waiting on it.

#### Scenario: Linking by address
- GIVEN a contact Sam holding sam@example.org
- WHEN a Waiting For task picks Sam on its Waiting on row
- THEN the task carries `CONTACT;ALTREP="mailto:sam@example.org":Sam`, and Sam's page lists it

## MODIFIED Requirements

## REMOVED Requirements
