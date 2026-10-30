---
cairn: tasks
change: task-batch-actions
---

# Tasks

- [x] Design boards on the canvas (Filters 5 and 6)
- [x] Review the proposal and its Open points (the user, 2026-10-30: approved as drawn)
- [x] Selection: long press, tap to toggle, the row's box as the selection mark, top bar cross / count / select-all, back ends it
- [x] Action bar in place of the bottom navigation while selecting: Category, Status, Due, Move, Copy, Delete; strings en and fr
- [x] Category sheet: tri-state boxes over the selection, a field for a new category, *Apply* computing each task's own list (`TaskEdit.withCategories`, `withList`)
- [x] Status, Due: one edit per task (`withStatus`, `withDone`, `withCancelled`, `withDue`)
- [x] Move: `TaskWrites.moveTo` per task, the ones already there skipped; Copy: `TaskWrites.create` with a new `UID`
- [x] Delete: confirmation with the count, `TaskWrites.remove` per task
- [x] Tests (`TaskBatchTest`): the category merge per task (ticked, blank, partial), the state a list change leaves, parents copied before children, what a copy keeps
- [ ] Not tested: the new UID of a copy (`TaskWrites.create` mints it) and the refused write reported once, the others kept (checked on the device)
- [ ] Device check (the user)
- [ ] Spec delta folded (tasks.md), log, CHANGELOG
- [x] Build: `:app:assembleDebug`, `:app:testDebugUnitTest` (796, `TaskBatchTest`)
