---
cairn: tasks
change: sync-strip-in-app-bar
---

# Tasks

- [x] activity_main.xml: app bar wrapped in a frame; title slot holds `bar_title` and the strip's line (`bar_strip`, `bar_strip_line`, `bar_strip_glyph`); `bar_strip_bar` on the bottom edge
- [x] sync_strip.xml and the includes in panel_mail, panel_contacts, panel_calendar removed
- [x] `ListHeader`: state per list, `renderStrip` into the shared bar while the list is the screen, `hideStrip`, pulse on the glyph's tag; search field and chips no longer step aside
- [x] `MainActivity`: `applyChrome` hides the strip, `showDomainBar` draws the list's
- [x] Strings en and fr: `sync_line_account`
- [x] Test: `SyncStripLayoutTest` (title and content tops unchanged as the strip shows and hides)
- [x] CHANGELOG [Unreleased]
- [x] Fold the delta into spec/offline-store.md and spec/mail.md, log entry
- [x] `:app:testDebugUnitTest`: 522 run, `SyncStripLayoutTest` green; one failure in `MailBodiesTest`, the body-download work in flight
- [ ] `:app:assembleDebug` (not run, at the user's request)
- [ ] Device test (the user): strip on mail, contacts, calendars, at the top and scrolled down, during a selection; title and list never moving
