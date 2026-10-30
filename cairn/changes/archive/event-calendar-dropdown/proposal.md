---
cairn: change
id: event-calendar-dropdown
status: landed
created: 2026-10-30
---

# A new entry opens at once, its calendar a dropdown on top; full-width dialog fields

## Why

The user's device round of 2026-10-30: the agenda's add button asked for a calendar before showing anything, and the entry page's Calendar section at the bottom (calendar, account, kind, read-only line) was not needed. Separately, the entry page's edit dialogs drew their field only as wide as its text: EventView.dialog put it in a horizontal LinearLayout, whose child wraps by default.

## What

- The add button opens the page at once, in the account's default calendar else the first writable one.
- While creating, a dropdown on top of the page names each writable calendar with its account, shown when there are several; switching keeps what the form holds, the alarms following whether the calendar takes them.
- The bottom Calendar section goes.
- The entry page's dialog fields take the dialog's width.
