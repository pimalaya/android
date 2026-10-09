---
cairn: change
id: calendar-conflict-form
status: active
created: 2026-10-09
---

# Calendar conflicts settled in a form, as contacts' are

## Why

A calendar has no conflict handling at all: when an entry is edited here and on the server since their last agreed base, the engine marks the binding conflicted (`bindings.conflict_object`, spec/conflicts.md) and nothing ever settles it. With the phone calendar mirror (`phone-calendar-mirror`) a second source can diverge the same way. Losing either side silently is not an option: data loss is worse than a question.

Contacts already answer this (spec/conflicts.md, `MainActivity.openConflict`): the sync triages every conflicted row through the three-way merge first, a clean merge resolves silently, and only a field both sides changed differently reaches a form that offers both values. Calendars take the same path.

## What

- **Triage in the pass:** a conflicted calendar binding, server or phone, is merged by ical-rs's `IcalMerge` (base, local, the recorded remote). A merge with no conflict is staged as the resolution and pushed by the next pass; the entry never shows as conflicted.
- **The form:** an entry with a genuine collision shows the conflicted mark in the agenda; opening it opens the entry page in conflict mode, each colliding field offering both values as chips, the newer side (`LAST-MODIFIED`, else `DTSTAMP`) pre-filled. Overrides are fields of their own occurrence: a collision on one occurrence is shown on that occurrence. `IcalMerge`'s `Recurrence` conflict (the series' rule changed on both sides) is a field like any other.
- **Saving** stages the resolution on the binding that conflicted, as contacts do; the next pass pushes it to that source.
- **Needs** in ical-rs: `a-removed-component-comes-back-for-an-edit` (an override deleted on one side and edited on the other must keep the edit whichever side is left) and `a-conflict-names-its-sides` (the conflict type the form reads), both cairn changes of ical-rs, implemented there and consumed by path dependency until a release.
