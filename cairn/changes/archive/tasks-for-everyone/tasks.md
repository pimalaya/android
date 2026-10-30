---
cairn: tasks
change: tasks-for-everyone
---

# Tasks

Both steps land together, then the user checks them on the device (user, 2026-10-30).

## 1. Filters and the default view

- [x] TasksList: Inbox, Waiting, Someday through Chips.exclusive (Inbox count kept in its label), the Next and Review chips removed
- [x] TasksList: the category row through Chips, any number on, no All chip (task_context_all removed), a task kept when it carries any category on
- [x] TasksList: the default view, open next actions merged in Next's order, sectioned Due and No due date; a row's line naming calendar and categories
- [x] Clarify and Weekly review as bar buttons on the Tasks list (activity_main.xml, MainActivity chrome), the in-header Clarify pill removed; Material Symbols `inbox_customize` for Clarify, the existing checklist for the review
- [x] Header review line hidden until a review was finished; task_review_never removed
- [x] Unit tests: the narrowing by chip and by categories (a helper with no Android view)
- [x] `:app:assembleDebug`, `:app:testDebugUnitTest` green

## 2. The task sheet

- [x] capture_sheet.xml and CaptureSheet: New task, calendar dropdown, three exclusive state chips, a categories field turning entries into removable badges (the composer's recipient chips as the model)
- [x] `open(state)` callers: FAB and share sheet and Add to tasks with none, widget and launcher shortcut with Inbox
- [x] TaskWrites: a new task staged with the state picked (or none) and the categories typed, through the GUIDE §17 writer; tests on the staged body (no state, Waiting plus two categories)
- [x] FAB label Add task; strings in values and values-fr
- [x] Next widget header opens the default view (ACTION_NEXT clears the chips)
- [x] Build, tests, install; device check by the user pending

## 3. Land

- [x] Fold delta.md into cairn/spec/tasks.md
- [x] cairn/log/YYYY-MM-DD-tasks-for-everyone.md
- [x] CHANGELOG.md Unreleased: the Tasks bullet reworded
- [x] cairn/verify.sh conformant; status landed, folder to changes/archive/
