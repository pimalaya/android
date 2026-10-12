---
cairn: change
id: sync-dock
status: active
created: 2026-10-12
---

# The sync strip becomes a dock above the bottom bar

Follows `sync-strip-in-app-bar` (2026-10-12).

## Why

Inside the app bar, the strip shares 60dp with the title: one 12sp line under it and a 4dp bar on the bar's edge. There is no room for both the account and what the pass is doing, and the slot is the one the large title hands its name to once it scrolls away, so the two compete. A toast was considered and dropped: a toast is transient by nature, and a permanent one would sit over the extended FAB and the last rows.

What a pass needs is a place that is always on screen, whichever list is shown and however far it is scrolled, reached the same way from a pull at the top and from *Load more* at the bottom, roomy enough for the account and a short log line. Music apps solved this with the docked mini player.

## What

### The dock

- A strip docked on top of the bottom navigation, on every list the bottom bar shows (mail, contacts, calendars, files): `@color/surface`, 64dp min height, a 1dp `divider_light` line between it and the bottom bar. It shows while a pass runs and goes when it ends.
- Along its top edge, the one progress bar (`sync_bar`, 3dp), same rules as today: one monotonic bar over the whole pass, indeterminate only before anything is counted.
- Left: a 22dp accent spinner; for the mail fill (*Load more*, background download), the download glyph (`ic_download`) instead.
- Middle, two lines:
  - the account, 14sp bold (`textColorPrimary`); *Starting the sync* before the pass reaches one;
  - the short log line, 13sp `textColorSecondary`: the domain then the current step, *Syncing mail · Downloading 42 messages*, *Loading older mail · August*, *Waiting for Wi-Fi*.
- Right: *1 of 2* (13sp secondary, tabular figures) when the pass has several accounts, then an expand chevron.
- The list's content ends above it; the extended FAB rises by the dock's height while it shows (its bottom margin from 96dp to 160dp) and comes back after. The large title, the search field, the chips and the rows do not move.
- The app bar is left as it was before `sync-strip-progress`: `bar_strip`, `bar_strip_line`, `bar_strip_glyph` and `bar_strip_bar` leave `activity_main.xml`.

### Opened

Tapping the dock opens a bottom sheet (28dp top corners, a drag handle):

- *Syncing* (22sp bold), the percentage, a collapse chevron; the same bar, 4dp, under it.
- One card, a row per account of the pass: a spinner and *Syncing mail · Downloading 42 messages* for the one running, an empty ring and *Next* for the ones after it, a check and *Done* for the ones finished.
- *RECENT* (the `SectionHeader` style), then one card of the pass's last lines, newest first, each with its time (`HH:mm:ss`, secondary, tabular figures): *alex@example.org · Mailboxes synced: 3 of 12*.
- Tapping the scrim or the chevron closes it back to the dock. The sheet closes by itself when the pass ends.

### The log

- The line now names the engine's step after the domain; this reverses *never the engine's steps* from `sync-strip-progress`. The strings exist (`sync_step_*`, `sync_line_*`, `mail_filling`, `sync_line_wifi`).
- The pass keeps its lines in memory, the last 50, for the sheet; nothing is written to disk and the list is dropped when the next pass starts.

## Designs

`designs/`, 390×844:

| File | Frame |
|------|-------|
| `SyncDock.dc.html` | A pull: the dock over the mail list's bottom bar |
| `SyncDockOpen.dc.html` | The dock opened: accounts and the short log |
| `SyncDockMore.dc.html` | *Load more* at the list's end: the dock just under it |

HTML mockups to read for structure, copy, sizes and colours, not code to port.

## Out of scope

- The reader and the composer have no bottom bar, so no dock: a pass started from a list keeps running and shows again on return. To revisit if that proves confusing.
- Cancelling a pass from the dock.
