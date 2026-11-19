---
cairn: delta
change: task-avatar
---

## ADDED Requirements

## MODIFIED Requirements

### Requirement: A task's STATUS is a view
In progress SHALL list the open tasks carrying `STATUS:IN-PROCESS` (RFC 5545 3.8.1.11), and every other list SHALL mark such a task In progress; the task page SHALL set it, writing `STATUS:IN-PROCESS`, and clear it, writing `STATUS:NEEDS-ACTION`. Done SHALL list the `COMPLETED` tasks and Cancelled the `CANCELLED` ones; a tap on a row's avatar there SHALL reopen the task, writing `STATUS:NEEDS-ACTION` and clearing `COMPLETED` and `PERCENT-COMPLETE`, its categories kept. A new task SHALL carry no `STATUS`, a task carrying none reading as `NEEDS-ACTION`.

#### Scenario: A task ticked by mistake
- GIVEN a Waiting item
- WHEN its row's avatar is tapped, then Done is on and its avatar tapped again
- THEN it carries `STATUS:NEEDS-ACTION` and no `COMPLETED`, and the Waiting chip lists it

#### Scenario: Under way
- GIVEN a next action set in progress from its page
- WHEN In progress is on
- THEN the tab lists it, and with no chip on its row says In progress

### Requirement: A task opens on its page
Tapping a task's row SHALL open its page, an open Inbox item opening the clarify flow instead. The row SHALL lead with its task list's avatar, as an agenda entry does: the list's initial on the list's hue, its declared colour's when it has one, else the hue derived from the list's id. A tap on the avatar SHALL close the task, or reopen a closed one; an open Inbox item under the Inbox chip SHALL carry the Inbox glyph in the letter's place, and a tap on it SHALL open the clarify flow on it. A done task's avatar SHALL keep its list's hue and show a check in the letter's place, a cancelled one's a cross, the row dimmed; a picked row's avatar SHALL be the accent disc and its check, as in every list. The page SHALL take the entry page's shape: Details (the title, the notes, the priority), When (the due date), Progress (the status, To do, In progress, Done or Cancelled, and who a Waiting For item waits on), Organise (the GTD list, none, Inbox, Waiting or Someday; the categories; the task it sits under), the mail it came from, and its calendar. Each row SHALL open a dialog; a field the task has not got SHALL be hidden until its section's add button reveals it; a save SHALL write what moved as one edit, the status included: Done writing `STATUS:COMPLETED` and its `COMPLETED` time, Cancelled `STATUS:CANCELLED`, To do and In progress `NEEDS-ACTION` and `IN-PROCESS`, clearing `COMPLETED` and `PERCENT-COMPLETE` from a closed task.

#### Scenario: A tap on a next action
- GIVEN a next action listed with no chip on
- WHEN its row is tapped outside the avatar
- THEN its page opens and the task is still open

#### Scenario: A tap on the avatar
- GIVEN a next action in a task list named Personal, its row's avatar showing P
- WHEN the avatar is tapped
- THEN the task carries `STATUS:COMPLETED`, and its row under Done shows a check on the avatar in P's place

#### Scenario: Done from the page
- GIVEN a task's page
- WHEN its status is set to Done and the page saved
- THEN the task carries `STATUS:COMPLETED` and a `COMPLETED` time, and Done lists it

## REMOVED Requirements
