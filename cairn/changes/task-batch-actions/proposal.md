---
cairn: change
id: task-batch-actions
status: active
created: 2026-10-30
---

# Tasks: select several, then act on them at once

Design: the canvas row "Filters, one panel; sync apart (proposed)", boards Filters 5 and 6.

## Why

User feedback (2026-10-30): the Tasks list has no way to act on several tasks. Changing a category, a status or a due date, copying, moving or deleting is done one task page at a time.

## What

### Selecting

- A long press on a row starts a selection holding that task, as on the mail and contacts lists. While it lasts a tap on a row adds or takes it out, and the row's leading box shows it selected (an accent box with a check) instead of its done tick, which takes no tap.
- The top bar shows the cross that ends the selection, the count ("3 selected") and the select-all box over the rows listed. Back ends it too. An empty selection ends itself.
- The bottom navigation gives way to an action bar of six, each an icon over its label: Category, Status, Due, Move, Copy, Delete. Six do not fit the top bar beside the cross, the count and select-all, and the list bar rule forbids an overflow.

### The actions

- **Category**: a sheet listing Inbox, Waiting, Someday, then every category any task carries, each box ticked when every selected task carries it, partly when some do, blank when none. Tapping cycles it to ticked or blank; *Apply* adds a ticked one to each task lacking it and takes a blank one off each carrying it, a box left partly ticked changing nothing. A field adds a new category.
- **Status**: To do, In progress, Done, Cancelled; one picked applies to every selected task, Done stamping `COMPLETED`.
- **Due**: the date picker; a day sets every selected task's `DUE` to it, *Clear* removes it.
- **Move**: the calendars taking tasks, under their accounts; one picked files every selected task there through the existing move (`TaskWrites.moveTo`: one add superseding the source, the `UID` kept). A task already there is left alone.
- **Copy**: the same picker; each selected task is added to the calendar picked as a new task, a new `UID`, everything else as it is.
- **Delete**: asks "Delete 3 tasks?", then stages each removal; they stay restorable from Deleted items until a purge.
- Every action is staged offline as one write per task, as a single edit from the task page is, and ends the selection. A write refused for one task (a calendar taking no tasks) is reported once, the others kept.

## Out of scope

- Undo beyond Deleted items.
- Batch actions on the mail filter's other lists.

## Open

- Copy and parents: a copied subtask keeps its `RELATED-TO` only when its parent is copied with it; otherwise it lands on its own. A copied parent's children are not copied unless selected.
- Move of a parent leaves its subtasks where they are, their `RELATED-TO` kept (the relation decides nothing else).
- Status on a task in the Inbox: Done takes it out of every open list, its `inbox` category left as it is, as the task page does today.
- The action bar labels in French run long ("Supprimer", "Catégorie"): 12sp in a sixth of the width each, to check on the device.
