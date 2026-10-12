---
cairn: log
change: sync-strip-in-app-bar
landed: 2026-10-12
---

# The sync strip becomes the app bar's extension

Capabilities moved: offline-store (modified: the strip is drawn within the app bar, its line under the title and its bar on the bar's bottom edge, moving neither title nor list; the search field and chips stay while it shows), mail (modified: the background download shows in the app bar's strip).

The strip no longer is a row of its own under the app bar, which pushed the large title and the list down each time it showed. activity_main.xml wraps the app bar in a frame: the title's slot holds the title and, at its bottom, the strip's one line (the step, then the account after a middle dot, `sync_line_account`; *Loading older mail* with its pulsing glyph), and the frame lays the same thin bar (sync_bar drawables) on the bar's bottom edge, both within the bar's height. sync_strip.xml and the panels' includes are gone. The bar being shared, `ListHeader` keeps each list's strip state and draws it into the bar only while that list is the screen (`renderStrip`, from `MainActivity.showDomainBar`); `applyChrome` takes it off every other screen (`ListHeader.hideStrip`), the glyph's pulse riding on the glyph's tag. The search field and the chips no longer step aside while a pass shows, which was a bump of its own. `SyncStripLayoutTest` checks that the title's and the content's tops do not move as the strip shows and hides.
