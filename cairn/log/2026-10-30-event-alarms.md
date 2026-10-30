---
cairn: log
change: event-alarms
date: 2026-10-30
---

# An entry's alarms on its page, rung by the phone; icons in the add-field chooser

After the user's audit of the entry page on 2026-10-30.

- **calendar** (spec/calendar.md): a CalDAV event's page carries an Alarms section after When, its alarms read through the phone view (`CalendarClient.projectEvent`) and written through the mirror's patch (`CalendarClient.applyEvent`, rs/calendar/phone.rs), so a kept alarm stays byte for byte and one the view does not carry, an email alarm or an absolute one on a series, is never touched; presets for timed and all-day entries; a save changing alarms alone asks no scope, a split carrying them to both halves. The app rings nothing itself: the phone's calendar provider rings what the mirror projects. Microsoft and Google entries, to-dos, journal entries and conflicts show no alarm section. The add-field chooser is one (ui/FieldChooser, from the contact page's), each entry and task field with its icon.

Capabilities moved: calendar.

Verified on 2026-10-30: `gradle :app:assembleDebug :app:testDebugUnitTest` green (791 tests), EventWritesTest's alarm cases on code whose Save carried no alarms; cairn/verify.sh conformant. Device check pending.
