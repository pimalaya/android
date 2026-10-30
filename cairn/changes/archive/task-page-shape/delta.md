---
cairn: delta
change: task-page-shape
---

Folds into spec/tasks.md.

## ADDED Requirements

### Requirement: A task opens on its page
Tapping a task's row SHALL open its page, an Inbox item opening the clarify flow instead, the row's tick alone closing or reopening it. The page SHALL take the entry page's shape: Details (the title, the notes, the priority), When (the due date), Progress (the status, To do, In progress, Done or Cancelled, and who a Waiting For item waits on), Organise (the GTD list, none, Inbox, Waiting or Someday; the categories; the task it sits under), the mail it came from, and its calendar. Each row SHALL open a dialog; a field the task has not got SHALL be hidden until its section's add button reveals it; a save SHALL write what moved as one edit, the status included: Done writing `STATUS:COMPLETED` and its `COMPLETED` time, Cancelled `STATUS:CANCELLED`, To do and In progress `NEEDS-ACTION` and `IN-PROCESS`, clearing `COMPLETED` and `PERCENT-COMPLETE` from a closed task.

#### Scenario: A tap on a next action
- GIVEN a next action listed with no chip on
- WHEN its row is tapped outside the tick
- THEN its page opens and the task is still open

#### Scenario: Done from the page
- GIVEN a task's page
- WHEN its status is set to Done and the page saved
- THEN the task carries `STATUS:COMPLETED` and a `COMPLETED` time, and Done lists it

## MODIFIED Requirements

### Requirement: The Tasks chips are filters
The Tasks tab SHALL carry two chip rows. The category row, first, SHALL begin with Inbox, Waiting and Someday, at most one on, each counting its tasks under the status on, none under Done or Cancelled, then hold the person's categories (A category filter narrows every list). The status row, under it, SHALL be In progress, Done and Cancelled, at most one on, none on meaning the open tasks, In progress counting its tasks. Each chip SHALL be drawn as the mail tab's filter chips are. With no status on, Inbox, Waiting and Someday SHALL each keep the open tasks carrying that reserved value among their categories, compared folded, and none on SHALL be the next actions; under a status, none on SHALL be its tasks whatever their state, and one on SHALL narrow them to the tasks carrying it. Clarify and Weekly review SHALL be buttons of the Tasks list's app bar, not chips: Clarify shown while the Inbox holds an open item, Weekly review always.

#### Scenario: Turning a filter off
- GIVEN the Waiting chip on
- WHEN it is tapped again
- THEN no chip is on and the tab lists the next actions

#### Scenario: Clarify from the bar
- GIVEN an Inbox holding two items and no chip on
- WHEN the bar's Clarify button is tapped
- THEN the clarify flow opens on the first of the two

#### Scenario: A status within a list
- GIVEN a Waiting item set in progress and a next action set in progress
- WHEN In progress is on, then Waiting too
- THEN In progress lists both, and with Waiting on, the Waiting item alone

## REMOVED Requirements
