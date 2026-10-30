---
cairn: change
id: tasks-for-everyone
status: landed
created: 2026-10-30
---

# The Tasks tab reads as a plain task list, GTD beside it

## Why

The user's device round of 2026-10-30 found the Tasks tab sound for a GTD practitioner but foreign to everyone else, and its chips unlike the rest of the app:

- **The top chips mix filters and actions.** Inbox, Next, Waiting and Someday switch a list, but Weekly review (TasksList.java:166) opens a screen, and Clarify (TasksList.java:181) is a button under the chips. In the mail tab every chip is a filter, on or off, none on meaning the default view (MailList.java:206, Chips.exclusive).
- **The default view is GTD's.** The tab opens on Next beside an Inbox, a Waiting and a Someday a newcomer has no reason to know. A plain task app opens on the open tasks of the lists picked, merged, as the mail tab merges its mailboxes.
- **The category row has an All chip** (TasksList.java:563) and takes one category at a time, where every other filter means "all" by having nothing on. Collections (calendars) are already filtered by the bar's filter page, the mail tab's way for mailboxes.
- **The add button captures.** The FAB is labelled Capture (MainActivity.java:2002) and every task it makes lands in the Inbox. The sheet can set nothing else: no other state, no category.

## What

- **Chips are filters.** The first row is Inbox, Waiting and Someday, at most one on, drawn by Chips like the mail tab's (check glyph when on, the Inbox chip keeping its count). A chip on keeps the tasks carrying that reserved category among theirs. Nothing on is the default view: the open tasks carrying none of the three (today's Next). The Next chip goes.
- **The second row is the categories, as tags.** Every category the open tasks carry, the three reserved values aside, one chip per folded spelling, any number on, nothing on meaning every task; a task stays when it carries any category on. The All chip goes. Collections stay with the bar's filter page.
- **The default view is one merged list** over the collections the filter shows: Next's order (due, priority, oldest captured), sectioned Due and No due date, subtasks under their parent, a row's second line naming its calendar and its categories.
- **Clarify and Weekly review move to the app bar** of the Tasks list, beside the filter button: Clarify (Material Symbols `inbox_customize`) shown while the Inbox holds an item, Weekly review (the existing ic_task_review) always. The header's review line shows only once a review was ever finished; "never done" goes.
- **One task sheet.** Titled New task: the title, the note, the calendar dropdown as today, three state chips Inbox, Waiting and Someday (at most one on, none meaning a next action: a task carrying two of them makes no GTD sense, though the format allows it), and a categories field whose entries become removable badges, as the composer's recipients do. Saved, the task carries the state picked and the categories typed, and nothing else.
- **Entry points.** The FAB reads Add task and opens the sheet with nothing on. The capture widget and the launcher shortcut, both named Capture, open it with Inbox on. The share sheet and a mail's Add to tasks open it with nothing on, the mail still linked by `mid:`.
- **Widgets follow.** The Next widget's header opens the default view; the capture widget's count opens the Inbox chip.

Choices made, to object to if wrong:

1. Several categories on keep a task carrying any of them (union, as the mail tab merges mailboxes), not all of them.
2. The launcher shortcut is a capture like the widget (both are labelled Capture today).
3. The FAB opens with nothing on even under a chip or with categories picked; the calendar dropdown keeps preselecting the last one used.

Out of scope: any change to how an existing task is edited.
