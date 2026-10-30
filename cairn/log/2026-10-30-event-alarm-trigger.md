---
cairn: log
change: event-alarm-trigger
date: 2026-10-30
---

# An alarm's trigger and repeat set in full

- **calendar** (spec/calendar.md): the alarm form sets when it rings as an amount of minutes, hours, days or weeks before or after the start or the end, or a date and time written in UTC, and how many more times it rings and how far apart; rs/calendar/alarm.rs writes an absolute trigger and a changed repeat.

Capabilities moved: calendar.

Verified on 2026-10-30: `cargo test` 537 green (calendar::alarm's new moment and repeat case), clippy clean, `gradle :app:assembleDebug :app:testDebugUnitTest` green; cairn/verify.sh conformant. Device check pending.
