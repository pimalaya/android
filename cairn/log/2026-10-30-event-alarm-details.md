---
cairn: log
change: event-alarm-details
date: 2026-10-30
---

# Every alarm with its type, message and recipients; entry page polish

After the user's device round of 2026-10-30.

- **calendar** (spec/calendar.md): a CalDAV event's page shows every alarm, whatever its action, read and written by rs/calendar/alarm.rs (`Native.readAlarms`, `Native.applyAlarms`) rather than the phone view: its type (Notification, Sound, Email, or as written), when it rings off the start, off the end or at a moment, its message, an email's recipients and its repeats; a row edits time, type, message, subject and recipients or removes it, a new alarm takes a type then a time, an email one addressed to the account. Alarm by alarm: a kept one byte for byte, a changed one only where it moved, a new one as RFC 5545 3.6.6 asks. The calendar dropdown sits in a card of its own; all-day flipped back with untouched dates gives back the times; a new entry has no delete; every save button shows a check.

Capabilities moved: calendar.

Verified on 2026-10-30: `cargo fmt`, clippy clean, `cargo test` 536 green (calendar::alarm's four new tests), `gradle :app:assembleDebug :app:assembleRelease :app:testDebugUnitTest` green (791 tests); cairn/verify.sh conformant. Device check pending.
