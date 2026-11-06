---
cairn: log
change: the-add-button-away-takes-no-tap
date: 2026-11-06
---

# The add button away takes no tap

A bug the owner found on the device on 2026-11-06, folded without a proposal.

Capabilities moved: offline-store (modified: a running sync shows a dock, the add button shrinking away while it shows rather than rising above it, which the spec still said, and taking no tap).

While the sync dock or a list's filters show, `MainActivity.refreshFab` scales the extended add button to nothing; a view scaled to nothing still takes taps in its layout bounds, so tapping where the button had been opened it during a sync. The button is now unclickable whenever it is away, in `refreshFab` and when a screen sets it up.
