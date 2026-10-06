---
cairn: log
change: compose-crash-and-row-alignment
landed: 2026-09-09
---

# The add button opens a page again, and a row's marks sit on the line they describe

Three things the calendar and the mail list had wrong, none of them about syncing.

**The agenda's add button crashed the app.** The entry page draws two rows about the occurrence that was tapped, the instance a recurrence rule placed and how far off it is, and an entry being composed was tapped from nowhere: `compose` opens the page with no occurrence, because the entry is not placed until it is saved. The countdown row read `occurrence.start` unconditionally and the page never finished drawing. Both rows are now drawn only for a page opened from an occurrence, which is the honest reading of what they say: an entry with no placement has no instance to name and nothing to count down to.

**The sync dialog opened as a title over an empty line.** It named the pass "preparing" and left the line under it blank until a collection could be named, which for mail and calendar is a connect and a roster round away. Two lines that become one and then jump back are a different dialog laid out differently, which is what made it look wrong. The dialog now opens naming the domain the user asked to sync, over a line saying it is preparing, and both are replaced as the pass reaches a collection. Every entry point names its domain, so the line is never a leftover from the last sync.

**A mail row's date and markers lined up with nothing.** They shared a column beside all three lines, top-aligned, so the date landed near the subject and the markers under it near the sender, neither level with anything. A column can be top-aligned or centred and neither of those is "level with that line". The date now ends the subject's line and the markers end the mailbox-and-account line, each inside the line it belongs to, so the alignment holds by construction rather than by arithmetic. The subject and the date are baseline-aligned, which is what makes 16sp and 12sp read as one line; the icons are centred on theirs, an icon having no baseline.

Capabilities moved: mail, calendar, offline-store.
