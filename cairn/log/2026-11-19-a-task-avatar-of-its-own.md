---
cairn: log
change: a-task-avatar-of-its-own
date: 2026-11-19
---

# A task's avatar is its own

A follow-up to task-avatar, checked by the owner on the 0.3.0 (8) release build the same day: every task of one list showed the same disc, the list's initial on the list's hue, so the rows looked alike. The avatar now takes the task's title: its initial, on a hue derived from the title (`Avatar.paint` over the title, no declared colour), as the mail list derives a sender's. Closed, captured and picked tasks are drawn as before.

Code: android/app/src/main/java/org/pimalaya/task/TasksList.java, the row's `AvatarSlot.bind`. Test: TasksTabSyncTest `aTapOnTheDiscMarksTheTaskDone` asserts the title's initial (C for Call Ann), which the old row failed with M, the list's. Spec: tasks.md, "A task opens on its page", the avatar's sentence and the scenario "A tap on the avatar".
