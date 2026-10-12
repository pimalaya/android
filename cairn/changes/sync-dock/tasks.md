---
cairn: tasks
change: sync-dock
---

# Tasks

- [x] Review the proposal and the designs
- [x] `activity_main.xml`: the dock between the content and `bottom_nav`, shown on the lists only; `bar_strip*` removed from the app bar
- [x] Dock content: top-edge bar (`sync_bar`, 3dp), spinner or download glyph, account line, log line, *1 of N*, chevron
- [x] New `SyncDock` class: one dock shared by every list, `ListHeader` loses `renderStrip`/`hideStrip`
- [x] Log line: domain then the engine's step; the `sync_step_*` strings, dropped in c5a31e5, restored
- [x] Keep the pass's last 50 lines in memory with their time; reset when a pass starts
- [x] Extended FAB: rises by the dock's height while it shows (translation, animated)
- [x] The list's bottom: the flipper ends above the dock, so no padding is needed; a list at its end scrolls on by the dock's height
- [x] Bottom sheet: title, percentage, bar, one row per section (running, next, done), RECENT log; closes on scrim, chevron, or the pass's end
- [x] *Load more* and the background fill report through the dock
- [x] Accessibility: the dock is one button ("Sync in progress, show details"); its line is a polite live region
- [x] `SyncDockLayoutTest` replaces `SyncStripLayoutTest`: title and content tops unchanged, content ending at the dock's top
- [x] Strings, English and French
- [x] Build: `:app:assembleDebug` green, `:app:testDebugUnitTest` 523 run, 0 failures
- [x] Fold the delta (spec/offline-store.md, spec/mail.md) and log
- [x] CHANGELOG [Unreleased]
- [ ] Device test (the user): dock on mail, contacts, calendars, files, at the top and scrolled to the end; *Load more*; sheet with two accounts; dark theme
