---
cairn: log
change: phone-deletions-held
date: 2026-11-06
---

# A mass deletion on the phone waits for the user

Landed in code on 2026-11-06 (5cf0f15), checked on the device by the owner the same day.

Capabilities moved: carddav-sync (added: a book wiped on the phone waits for the user), calendar (added: a calendar wiped on the phone waits for the user).

The phone pass pushed upstream whatever another app deleted from a mirrored book or calendar, and erased the phone row before the store recorded the deletion. More than half of at least 20 members deleted at once is now held in `PhonePass.enumerate`: the rows stay deleted on the phone, and when the app's sync ends a dialog offers to delete them on the server, put them back or decide later. A deleted row is erased only after the round that took its deletion. Tests: CalendarMirrorTest (a wipe held, released, restored, a few deletions unasked).

Left: no fake ContactsContract provider tests the contacts spoke as FakeCalendarProvider does the calendar one (production-release section 1); the aggregation of books the user does not own (audit-s1-residuals I7).
