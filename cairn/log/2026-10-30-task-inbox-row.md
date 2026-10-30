---
cairn: log
change: task-inbox-row
date: 2026-10-30
---

# The Inbox chip counts nothing; an Inbox row carries the Inbox glyph

- **tasks** (spec/tasks.md): the Inbox chip shows no count, the bar's Clarify button already saying the Inbox holds items; an open Inbox row carries the Inbox glyph in its tick's slot, which opens the clarify flow on it, rather than an empty gap.

Capabilities moved: tasks.

Verified on 2026-10-30: `gradle :app:assembleDebug :app:testDebugUnitTest` green; cairn/verify.sh conformant. Device check pending.
