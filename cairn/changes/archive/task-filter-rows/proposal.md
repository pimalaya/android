---
cairn: change
id: task-filter-rows
status: landed
created: 2026-10-30
---

# Two filter rows: status, then categories

## Why

The user's device round of 2026-10-30, after task-status-views: one first row mixed the lifecycle (In progress, Done, Cancelled) with the GTD lists, and the category row vanished from the default view whenever no next action carried a category of the person's, since it listed only the categories of the list shown. The user asked for two rows, one per concern: status, and categories, Inbox, Waiting and Someday being categories too (pimdir GUIDE §17).

## What

- **The status row**: In progress, Done, Cancelled, at most one on; none on is the open tasks. In progress counts its tasks.
- **The category row**: Inbox, Waiting and Someday first, at most one on, each counting its tasks under the status on (no count under Done or Cancelled); then the person's categories the tasks of that status carry, any number on, a category on kept and shown across statuses. The row is never empty.
- **The two combine**: with no status, a GTD list keeps its open tasks and none on is the next actions; under a status, none on is any state and a GTD list narrows it, since a started or closed task keeps the state it had (In progress within Waiting, Done within Waiting).

Not done: a status chip keeping the GTD state of a closed task out of view; a closed task's categories stay as they were.
