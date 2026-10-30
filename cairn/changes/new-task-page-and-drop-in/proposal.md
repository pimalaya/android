---
cairn: change
id: new-task-page-and-drop-in
status: active
created: 2026-10-30
---

# New task on the task page, and pages that drop in

## Why

User feedback (2026-10-30):

- The Tasks tab's add button, labelled Add task, opened the capture sheet: a title, a state and categories. Every other domain creates on the same page it edits on (an event on the entry page), and a new task deserves the same fields as an edited one.
- Pages slid in from the right and back out, and the onboarding steps slid between each other. The app had dropped slides long ago: the one transition kept is the drop-in of an account's settings.

## What

- The add button reads New task and opens the task page empty: the same sections and dialogs as editing, its calendar a picker among the calendars taking tasks, the one the last task went to first. Save files one create, asking for a title first when there is none. The capture sheet stays for the capture widget, the launcher shortcut, the share sheet and a mail's Add to tasks.
- Every page opened from a list drops in (`fade_zoom_in`, as the overlays); leaving a page or an overlay is not animated. The onboarding steps swap with no animation. The slide animations and `fade_zoom_out` are gone from the resources. The drawer keeps its own slide.
- The task page follows the entry page: its calendar first, then Organise (the GTD list, who a waiting task waits on, the categories, the task above), Details (the title, the notes, the priority, the due date), Progress (the status). When goes, holding the due date alone. Who a task waits on moves from Progress to Organise: it is the `CONTACT` pimdir GTD.md ties to a waiting task, a link deciding nothing, and Waiting is a state, not a `STATUS` (RFC 5545 3.8.1.11 offers `NEEDS-ACTION`, `IN-PROCESS`, `COMPLETED`, `CANCELLED`).
- A page creating or editing an item (a contact, an editable event, a task, the composer) shows a cross in place of the back arrow; it leaves unsaved, as back did.
