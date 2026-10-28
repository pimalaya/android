---
cairn: log
change: gtd-by-categories
date: 2026-10-28
---

# GTD by categories

The Tasks tab keeps its experience and replaces `X-PIMALAYA-GTD` with the convention pimdir GUIDE §17 states: a task's state is one of three reserved `CATEGORIES` values, `inbox`, `waiting` and `someday`, compared folded, written as those literals and shown under the app's labels; an open task carrying none is a next action. `X-PIMALAYA-GTD` is neither read nor written; nothing carrying it was released, so there is no migration. The writer moves only the three values, keeping every other category in order and every line holding none of them byte for byte (rust/src/calendar/task.rs and edit.rs, `set_state`). Projects and Reference leave the tab, which lists Inbox, Next, Waiting and Someday under a category filter; a subtask shows under its parent and the parent decides nothing. The capture sheet picks its calendar in a dropdown, the last used preselected and no default kept in account settings; clarify edits the same item, deletes or files it in Someday, and files it in another calendar as an add then a removal once the add was accepted. Send and wait for a reply writes `waiting`, `CONTACT` and the `mid:` link.

- **tasks**: modified "A task is a VTODO read the GTD way", "A task write patches the object", "A capture is on the phone at once", "Only CalDAV calendars take tasks", "Clarifying never puts an item back", "The weekly review counts, it does not guess", "Captures go to the calendar last picked" and "An account says whether it takes tasks"; added "A category filter narrows every list" and "Filing a capture elsewhere never loses it". Guarded by the task module's Rust tests (eleven new or rewritten, each failing against the former code) and TaskWritesTest.
- **mail**: modified "The composer sends and waits for a reply".
- **calendar**: unchanged.

Landed with two items open in the change's tasks.md: the design canvas, and the device run.
