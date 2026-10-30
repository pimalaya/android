---
cairn: log
change: task-page-shape
date: 2026-10-30
---

# A task opens on a page shaped like the entry page; categories above status

After the user's device round of 2026-10-30, where tapping a task never opened it.

- **tasks** (spec/tasks.md): a row's tap opens the task's page; it never did, the tick's ImageButton making itself focusable whatever item_task.xml said, so the list handed the row no tap, and the row now blocks its descendants' focus as item_message.xml does (TaskRowTest, failing on the earlier layout). The task page takes the entry page's shape: Details, When, Progress with a Status row (To do, In progress, Done, Cancelled), Organise with a GTD list row, From, Calendar; empty fields hidden behind each section's add button; one write on save, the status included. The category row is now the first, the status row under it.

Capabilities moved: tasks.

Verified on 2026-10-30: `gradle :app:assembleDebug :app:testDebugUnitTest` green, TaskRowTest red before the layout fix and green after; cairn/verify.sh conformant. Device check pending.
