---
cairn: change
id: task-page-shape
status: landed
created: 2026-10-30
---

# A task opens on a page shaped like the entry page; categories above status

## Why

The user's device round of 2026-10-30, after task-filter-rows: tapping a task never opened it. The row's tick is an ImageButton, which makes itself focusable whatever its layout says, so the list handed no tap to the row (TaskRowTest fails on the earlier item_task.xml); only the tick answered, which read as "a tap resolves it". The task page behind it had drifted from the entry and contact pages: a hand-built chip row with a Next chip on top, a "None" on every empty field, the title in a Details section near the bottom, and loose Mark done, Start and Drop rows. And the user wants the category row first, the status row second.

## What

- **Rows take their tap**: item_task.xml blocks its descendants' focus, as item_message.xml does.
- **The task page on the entry page's shape** (EventView): Details (title, then notes and priority), When (due), Progress (a Status row: To do, In progress, Done, Cancelled; who a Waiting For item waits on), Organise (a GTD list row: none, Inbox, Waiting, Someday; categories; the task it sits under), From (the mail), Calendar. Each row opens a dialog; an empty field stays hidden until its section's add button reveals it; everything is saved as one write, the status included (Done writes `COMPLETED` and its time, Cancelled `CANCELLED`, To do and In progress `NEEDS-ACTION` and `IN-PROCESS`, clearing `COMPLETED` and `PERCENT-COMPLETE` from a closed task).
- **Categories first**: the category row is the header's chip row, the status row under it.
