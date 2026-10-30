---
cairn: delta
change: task-batch-actions
---

Folds into `spec/tasks.md`.

## ADDED Requirements

### Requirement: Several tasks are selected by a long press
A long press on a Tasks row SHALL start a selection holding that task; while it lasts a tap SHALL add a row or take it out, the row's leading box showing it selected and taking no tap. The top bar SHALL carry the cross ending it, the count selected and a select-all box; back SHALL end it, and so SHALL an empty selection. The bottom navigation SHALL give way to an action bar of Category, Status, Due, Move, Copy and Delete.

#### Scenario: Starting a selection
- GIVEN the Tasks list
- WHEN a row is long pressed, then another tapped
- THEN the bar reads 2 selected and the action bar stands where the navigation was

### Requirement: An action applies to every selected task
Category SHALL offer Inbox, Waiting, Someday and every category any task carries, each ticked when every selected task carries it, partly when some do, blank when none; *Apply* SHALL add each ticked one to the tasks lacking it and take each blank one off the tasks carrying it, a partly ticked one changing nothing. Status SHALL set the picked state on each task, Done stamping `COMPLETED`. Due SHALL set or clear each task's `DUE`. Move SHALL file each task in the picked calendar as a single move does, a task already there left alone. Copy SHALL add to the picked calendar a new task per selected one, under a new `UID`, everything else kept. Delete SHALL ask with the count, then remove each task, restorable from Deleted items. Each action SHALL stage one write per task offline and end the selection; a write refused for one task SHALL be reported once and SHALL NOT undo the others.

#### Scenario: A category on part of the selection
- GIVEN three tasks selected, two carrying Garden
- WHEN Category is opened, Garden ticked and *Apply* pressed
- THEN the third task carries Garden too and the other two are unchanged

#### Scenario: Copying
- GIVEN two tasks selected in Personal
- WHEN Copy picks Errands
- THEN Errands holds two new tasks with new UIDs, and Personal still holds the two

## MODIFIED Requirements

## REMOVED Requirements
