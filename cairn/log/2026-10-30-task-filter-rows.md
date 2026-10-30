---
cairn: log
change: task-filter-rows
date: 2026-10-30
---

# Two filter rows: status, then categories

After the user's device round of 2026-10-30, where the category row vanished from the default view and one row mixed lifecycle and GTD lists.

- **tasks** (spec/tasks.md): the status row (In progress, Done, Cancelled, at most one on, none the open tasks) sits above the category row, which begins with Inbox, Waiting and Someday (at most one on, counted under the status on) and goes on with the person's categories of the tasks of that status, so it is never empty. Under a status, no GTD list on is any state and one on narrows it (TaskOrder.lists), so In progress within Waiting and Done within Waiting work again.

Capabilities moved: tasks.

Verified on 2026-10-30: `gradle :app:assembleDebug :app:testDebugUnitTest` green, TaskOrderTest's combinations on the new `lists(status, list, task)`; cairn/verify.sh conformant. Device check pending.
