---
cairn: change
id: event-alarm-trigger
status: landed
created: 2026-10-30
---

# An alarm's trigger and repeat set in full

## Why

The user's device round of 2026-10-30, after event-page-sections: the alarm form offered a handful of preset times, where RFC 5545 3.8.6.3 lets a trigger be any offset before or after the start or the end, or a moment, and an alarm repeat (3.6.6).

## What

- The form's time is a place (before or after the start, before or after the end, at a date and time), an amount and a unit (minutes, hours, days, weeks), or a date and time picked, written as a UTC date-time.
- A repeat row sets how many more times it rings and how far apart, in a unit; a repeat with no interval is none, `REPEAT` and `DURATION` going together.
- rs/calendar/alarm.rs writes an absolute trigger (`TRIGGER;VALUE=DATE-TIME`) and a changed repeat.
