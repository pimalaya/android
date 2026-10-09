---
cairn: change
id: ical-rs-0-6
status: active
created: 2026-10-09
---

# Calendar on ical-rs 0.6

## Why

The calendar code carried its own copies of what ical-rs 0.5.3 got wrong: a recurrence set rebuilt by hand so a UTC `UNTIL`, `EXDATE` or `RECURRENCE-ID` named an instance of a zoned series, a written time in a gap resolved by hand, an instant turned back into a local time by a two-step guess, every pushed property popped and re-inserted before the `VALARM`s, `RRULE`s read through the text decoder since `raw_value_str` cut them at the first `;`, and the stamps both sides of a merge wrote settled after the merge. ical-rs 0.6 fixes each upstream.

## What

- `IcalRecurSet::of_component_in` and `IcalRecurOverride::of_component` tell every time of a series on its start's clock; the app keeps only which override replaced which identity, and reads an override's own start off its `DTSTART`, the set now telling it on the series' clock.
- `IcalTzOffset::literal_offset`, `literal_instant` and `IcalTz::local` replace the hand resolution. The guess was wrong just past a gap: 01:30 UTC on the day Paris springs forward read as 02:30 rather than 03:30, so a phone edit there in a zone only the object defines landed an hour early.
- `IcalCst::push`, `push_raw` and `push_component` place what they add; `raw_value_str` reads a rule whole.
- The merge settles `DTSTAMP`, `LAST-MODIFIED` and `SEQUENCE` and no longer lands one side's zone on the other's time. The app settles the stamps only on a component a conflict takes whole from one side, which never passes through the merge.
- Written lines past 75 octets are folded (RFC 5545 3.1); a parsed line the app does not touch keeps its bytes.

ical-rs is consumed through a `[patch.crates-io]` path dependency until 0.6.0 is released, which also moves io-msgraph's and io-gcal's copy.
