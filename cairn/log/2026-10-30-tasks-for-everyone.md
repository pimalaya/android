---
cairn: log
change: tasks-for-everyone
date: 2026-10-30
---

# The Tasks tab reads as a plain task list, GTD beside it

After the user's device round of 2026-10-30: the tab felt right to a GTD practitioner and foreign to everyone else.

- **tasks** (spec/tasks.md): with no chip on, the tab lists the next actions of every calendar the filter shows, merged, sectioned Due and No due date, a row naming its calendar and categories; Inbox, Waiting and Someday are filter chips, at most one on, drawn as the mail tab's (ui/Chips), the Next chip gone; the category row takes any number of categories, a task kept when it carries any, nothing on meaning every task, the All chip gone; Clarify (shown while the Inbox holds an item) and Weekly review are app bar buttons; the header says nothing of the review before a first one is finished. The capture sheet is one task sheet: Inbox, Waiting and Someday chips, at most one on, and a categories field of removable chips, the composer's field moved to ui/ChipField for both; the add button (Add task), the share sheet and a mail's Add to tasks open it with no state, the capture widget and launcher shortcut with Inbox on. A state and categories are written as pimdir GUIDE §17 writes them, the categories a line and the state a line after it; a reserved value typed as a category is refused in the sheet.

Capabilities moved: tasks.

Verified on 2026-10-30: `gradle :app:assembleDebug :app:testDebugUnitTest` green (784 tests, LayeringTest included), the new TaskOrderTest and TaskWritesTest cases on code that did not have `carriesAny`, `reserved` or `TaskWrites.add`; cairn/verify.sh conformant. Device check pending.
