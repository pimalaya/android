---
cairn: log
change: sync-strip-progress
landed: 2026-10-10
---

# The sync strip shows one bar over the whole pass, names the domain, and nothing pops up after

Capabilities moved: offline-store (modified: the strip's progress requirement rewritten, one monotonic bar and a domain line; added: a sync that went through says nothing), mail (added: the background fill's download glyph).

**One bar.** Before a pass runs, `MainActivity.planSync` sizes it in sections, one per domain and account, each weighing the collections stored for it within the scope (one at least), the drawer's sync planning all three domains. `SyncSteps.across` places the section under way in the whole, `SyncSteps.permille` the collections landed within it, and `SyncSteps.shareOf` the counted step within a collection: the naming download (`PimdirEngine.named`) the first three fifths and the phone projection (`PhoneRemote.push`, `CalendarRemote.push`) the rest for contacts and calendars, the download all of it for mail. `PimdirEngine.Progress` tells a step's count at most once per percent (`SyncSteps.tells`); calendars tell theirs only while one runs alone. The strip keeps the highest value it showed, so a step starting at nothing holds the bar.

**One line.** The line names the domain (*Syncing mail*, *Syncing contacts*, *Syncing calendars*), never the engine's step; it opens on *Preparing synchronization*. A first-sync warning line was tried and dropped.

**Controls.** While the strip shows, the list's search field and chips are hidden, a field holding a query staying.

**No report.** The counts toast, the outbox's sent toast, the pending-conflicts toasts and the background-busy toast are gone; a pull turned down by a background run returns silently. Failures keep their dialog.

**Background fill.** `ListHeader.filling` shows a download glyph beside the mail count once a fill step worked, pulsing in opacity unless animations are off, its long press reading *Loading older mail*; it goes when the fill ends or pauses.
