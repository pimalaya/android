---
cairn: change
id: hide-in-recents
status: active
created: 2026-11-07
---

# Hide in recents

Later, not a priority (owner, 2026-11-07).

## Why

The app switcher shows a snapshot of each app's last screen: for Pimalaya, the open message, contact or agenda, readable to whoever holds the unlocked phone or watches a screen share (audit-s1-residuals I2).

## What

A switch on the Settings page, off by default, blanking the snapshot: `setRecentsScreenshotEnabled(false)` from Android 13, which blanks the thumbnail alone; `FLAG_SECURE` below, which also blocks screenshots and screen recording of the app, said in the switch's note.
