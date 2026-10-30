---
cairn: change
id: event-alarm-form-layout
status: landed
created: 2026-10-30
---

# The alarm form: one label and one input per value

## Why

The user's device round of 2026-10-30, after event-alarm-trigger: the form packed an amount, a unit and words on single lines, the interval's unit squeezed out of sight.

## What

One label and one input per value: Type, Trigger (before or after the start or the end, or at a date and time), When (a duration picked as an amount and a unit, or a moment picked as a day and a time, following the trigger), Rings again (a count), Every (a duration, shown while the count is above zero), then the message, and an email's subject and recipients.
