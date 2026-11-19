---
cairn: log
change: task-avatar
date: 2026-11-19
---

# A task row takes its list's avatar

Approved by the owner on 2026-11-19 from the design canvas frames TasksCheck and TaskSelectCheck. A task row in the Tasks tab no longer leads with an empty ring, its done box: it takes the avatar every other tab has, through the shared `AvatarSlot` (android/app/src/main/java/org/pimalaya/ui/AvatarSlot.java, android/app/src/main/res/layout/avatar_slot.xml).

- The avatar is the task list's: its initial on its hue, the list's declared colour's when it has one, else the hue derived from the list's id, as `CalendarList` paints an agenda entry.
- A tap on it does what the box's did, through the same `TasksList.done` write: closes an open task, reopens a closed one, opens the clarify flow on an open Inbox item under the Inbox chip, toggles the selection while selecting. A tap elsewhere still opens the task, a long press still selects.
- A done task's avatar keeps its list's hue and shows the check (drawable/ic_check.xml) in the letter's place, a cancelled one's the cross (drawable/ic_close.xml), an open Inbox item's the Inbox glyph (drawable/ic_task_inbox.xml), as the box did; the row is dimmed as before. A picked row's avatar is the accent disc and its check, at full strength on a closed row.
- `AvatarSlot.bind` gained an overload taking a glyph drawn in the disc's ink in the letter's place (`avatar_glyph` view, `avatar_glyph` dimen of 20dp, the canvas's). The box went: drawable/task_box.xml, the `task_box_stroke` dimen and the `task_done` button.

Tests: `TasksTabSyncTest.aTaskTickedAfterThePassFilingItIsDone` and `WritesAfterAPassTest.aTickOnARowThePassChangedIsAppliedToIt` tap the avatar in the box's place. The new `TasksTabSyncTest.aTapOnTheDiscMarksTheTaskDone` checks the open row's avatar shows the list's initial, that a tap on it closes the task, and that the done row's avatar shows the check, not the letter; on the old row it fails, the slot being the picked check's alone, its letter never set and no tap on it.

Capability moved: tasks ("A task opens on its page" and "A task's STATUS is a view" now name the avatar where they named the tick). The owner checks the release build on the device.
