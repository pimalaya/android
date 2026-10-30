---
cairn: change
id: event-alarms
status: landed
created: 2026-10-30
---

# An entry's alarms on its page, rung by the phone; icons in the add-field chooser

## Why

The user's audit of 2026-10-30: the entry page has no alarms, and its add-field chooser is a plain list where the contact page's names each field with its icon. The user's call: the app never rings an alarm itself; the phone does, through the calendar mirror it already syncs (the mirror projects a `VALARM` as a `Reminders` row the calendar provider schedules, cairn/changes/phone-calendar-mirror).

## What

- **An Alarms section** on the page of a CalDAV event, after When: one row per alarm the phone view carries (a display or audio alarm off the start or the end, or at a time on an entry that does not recur), labelled against the start ("15 minutes before", "On the day at 09:00" on an all-day entry, "At" a moment); a row picks another time or removes it, the header's add button adds one from presets (at the start, 5, 10, 15, 30 minutes, 1 and 2 hours, 1 and 2 days, a week before; on an all-day entry 09:00 the day of, the day before, two days before, a week before).
- **Written through the mirror's patch** (`phone::apply`, rs/calendar/phone.rs): an alarm kept stays byte for byte, a changed one keeps its other properties, a new one is a display alarm describing the entry, and an alarm the view does not carry (an email alarm, an absolute one on a series) is never touched. Alarms are the whole entry's: a save changing them alone asks no scope, and a split carries them to both halves.
- **Nowhere else**: not on Microsoft and Google entries, whose reminders the bridge does not map, nor on to-dos and journal entries, which the mirror does not project, nor in conflict mode.
- **Icons in the add-field chooser**: the contact page's chooser becomes ui/FieldChooser, shared by contacts, calendar entries and tasks, each entry and task field carrying a Material Symbols icon.

Not done: mapping Microsoft's and Google's reminders to `VALARM`; a custom alarm time beyond the presets.
