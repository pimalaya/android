---
cairn: change
id: task-status-views
status: landed
created: 2026-10-30
---

# A task's STATUS is a view: in progress, done, cancelled

## Why

The user's device round of 2026-10-30, after tasks-for-everyone: ticking a task made it vanish with no way back. Ticking writes `STATUS:COMPLETED` and Drop writes `STATUS:CANCELLED`, both kept in the calendar, but spec/tasks.md keeps a closed task out of every list, and a dateless one shows in no agenda either. And `STATUS:IN-PROCESS` (RFC 5545 3.8.1.11), which says a task is under way, is read by nothing.

`STATUS` and the GTD state are two axes: `STATUS` is the task's lifecycle (`NEEDS-ACTION` or none, `IN-PROCESS`, `COMPLETED`, `CANCELLED`), the reserved category where an open task lives while it is organised (pimdir GUIDE §17). Waiting is not `NEEDS-ACTION`: every open GTD list holds `NEEDS-ACTION` tasks, and the standard's delegation (`ATTENDEE;PARTSTAT=DELEGATED`, RFC 5546) is scheduling, which a CalDAV server may mail out. Done is `COMPLETED` and GTD's trash is `CANCELLED`.

## What

- **Three more chips in the first row**, still at most one on: In progress first, then Inbox, Waiting and Someday, then Done and Cancelled. Nothing on stays the open next actions.
- **In progress** lists the open tasks carrying `STATUS:IN-PROCESS`, whatever their GTD state, in Next's order; every other open view marks such a row In progress. The task page sets and clears it (Start, Not started), writing `IN-PROCESS` or `NEEDS-ACTION`.
- **Done** lists the `COMPLETED` tasks, the most recently completed first, and **Cancelled** the `CANCELLED` ones, the most recently captured first (the task carries no stamp of when it was dropped), their GTD state ignored; a row's tick reopens it (`NEEDS-ACTION`, `COMPLETED` and `PERCENT-COMPLETE` cleared, its category kept, so a Waiting item ticked by mistake goes back to Waiting). Their counts are not shown.
- **The category row** offers the categories of the tasks the view shown lists, the categories on staying across views.
- **Nothing written by default**: a new task carries no `STATUS`, none read as `NEEDS-ACTION`.

Not done: In progress within Waiting (one exclusive row), a Dropped stamp.
