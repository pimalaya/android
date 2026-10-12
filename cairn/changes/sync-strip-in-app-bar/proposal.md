---
cairn: change
id: sync-strip-in-app-bar
status: active
created: 2026-10-12
---

# The sync strip becomes the app bar's extension

## Why

The strip pinned under the app bar (sync-feedback-pinned) is its own row: each time it shows or hides, the large title and the list jump down or back up. It now shows often (every pass, *Load more*, the background download, waiting for Wi-Fi), so the list keeps bumping under the reader's eyes. Hiding the search field and the chips while a pass shows, meant to keep the list's height, adds a bump of its own.

## What

The strip moves into the app bar (activity_main.xml), drawn within the bar's own height:

- Its line sits in the title's slot, in the bar's lower part under the title: the step, then the account after a middle dot (*Syncing mail · me@example.org*), one line ellipsized, the download glyph pulsing beside *Loading older mail* on the mail list.
- Its bar lies on the bar's bottom edge, the same thin 4dp bar (determinate once counted, indeterminate before), overlaid on the bar rather than below it.

Showing, updating or hiding it changes neither the bar's height nor the title's position nor the content's top. When nothing runs, the bar looks as before. The bar being shared, a list draws its strip into it only while it is the screen (`ListHeader.renderStrip`, called as a list's chrome is applied); every other screen takes it off (`ListHeader.hideStrip` from `applyChrome`). The search field and the chips no longer step aside while a pass shows. sync_strip.xml and the panels' includes go; strings stay, one format added (`sync_line_account`).

MainActivity is touched only where the strip's views are bound: `applyChrome` hides it, `showDomainBar` draws the list's.

## Out of scope

- A Material components dependency: the bar keeps the platform `ProgressBar` and the existing drawables.
- The Files tab, which has no strip.
