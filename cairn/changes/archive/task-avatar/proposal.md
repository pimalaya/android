---
cairn: change
id: task-avatar
status: landed
created: 2026-11-19
---

# A task row takes its list's avatar

## Why

Every other tab leads its rows with an avatar through the shared `AvatarSlot` (mail, contacts, calendar entries, files), while a task row led with an empty ring of the avatar's size, its done box, and showed the shared avatar only while picked. The owner approved on 2026-11-19, from the design canvas frames TasksCheck and TaskSelectCheck, that a task row take the avatar too.

## What

- A task row's avatar is its task list's: the list's initial on the list's hue, its declared colour's when it has one, else the hue derived from the list's id, exactly as an agenda entry is painted (`CalendarList`).
- A tap on the avatar does what the box's did: closes an open task, reopens a closed one, opens the clarify flow on an open Inbox item under the Inbox chip, toggles the selection while selecting. A tap elsewhere on the row still opens the task, a long press still selects.
- A done task's avatar keeps its list's hue and shows a check in the letter's place, a cancelled one's a cross, an open Inbox item's the Inbox glyph (the box showed the same three glyphs); the row is dimmed as before.
- A picked row's avatar is the accent disc and its check, as in every tab.
- `AvatarSlot` gains a glyph in the letter's place (layout/avatar_slot.xml `avatar_glyph`, sized by the new `avatar_glyph` dimen), drawn in the disc's ink. The box (drawable/task_box.xml, the `task_box_stroke` dimen, the `task_done` button) goes.

The Inbox glyph is kept from the box: the canvas frames draw no Inbox view, and dropping the glyph would change what a tap on an Inbox item's avatar does, which the owner did not ask.
