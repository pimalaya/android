---
cairn: change
id: event-alarm-details
status: landed
created: 2026-10-30
---

# Every alarm with its type, message and recipients; entry page polish

## Why

The user's device round of 2026-10-30, after event-alarms: the page showed only the alarms the phone rings (display and audio), with no type, no message and no repeat, where an email alarm, which the phone never sends, is still another client's to act on (neverest on the desktop). And four smaller frictions: the calendar dropdown sat outside the page's cards, flipping all-day on and off lost the times set, a new entry offered a delete that back already does, and the save buttons showed a disk.

## What

- **Every alarm**, whatever its action (rs/calendar/alarm.rs, `Native.readAlarms` and `Native.applyAlarms`): a row says its type (Notification, Sound, Email, or the action as written) and when it rings (off the start, off the end, or at a moment), over its message, an email's recipients and how it repeats. A row edits its time, type, message, and an email's subject and recipients, or removes it; a new alarm picks its type then its time, its message the entry's title, an email one addressed to the account under that title. Written alarm by alarm: one kept stays byte for byte, one changed has only what moved rewritten, one dropped goes, one new is built as RFC 5545 3.6.6 asks of its action. Repeats are read and kept, not edited.
- **The calendar dropdown** in a card of its own, as the other sections.
- **All-day flipped back** with the dates untouched since gives back the dates and times it took.
- **No delete on a new entry**: back leaves it unsaved.
- **Save buttons show a check**, the disk icon gone.
