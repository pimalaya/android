---
cairn: log
change: task-status-views
date: 2026-10-30
---

# A task's STATUS is a view: in progress, done, cancelled

After the user's device round of 2026-10-30, where a ticked task vanished for good.

- **tasks** (spec/tasks.md): the first chip row reads In progress, Inbox, Waiting, Someday, Done, Cancelled, at most one on (TaskOrder.lists). In progress lists the open tasks carrying `STATUS:IN-PROCESS` whatever their GTD state, in Next's order, and every other open list badges them; the task page's Start and Not started write `IN-PROCESS` and `NEEDS-ACTION` (TaskEdit.withStatus). Done lists the `COMPLETED` tasks, the last completed first, and Cancelled the `CANCELLED` ones, the last captured first, their GTD state ignored and their chips uncounted; a row's tick there reopens the task (`NEEDS-ACTION`, `COMPLETED` and `PERCENT-COMPLETE` cleared, its category kept, so a Waiting item ticked by mistake returns to Waiting). The category row offers the categories of the list shown, the ones on staying across lists. Review and Clarify open and close with no slide (Host.showInstant), and a finished clarify pass returns to the list it was opened from.

Capabilities moved: tasks.

Verified on 2026-10-30: `gradle :app:assembleDebug :app:testDebugUnitTest` green (788 tests), the new TaskOrderTest and TaskWritesTest cases on code that had no `TaskOrder.lists`, `TaskOrder.COMPLETED` or `TaskEdit.withStatus`; cairn/verify.sh conformant. Device check pending.
