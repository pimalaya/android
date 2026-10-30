---
cairn: change
id: task-inbox-row
status: landed
created: 2026-10-30
---

# The Inbox chip counts nothing; an Inbox row carries the Inbox glyph

## Why

The user's device round of 2026-10-30: the Inbox chip's count repeated what the bar's Clarify button already says, and an Inbox row, whose tick is hidden since a capture is clarified rather than ticked off, kept an empty gap on its left.

## What

- The Inbox chip shows no count; Waiting, Someday and In progress keep theirs.
- An open Inbox row carries the Inbox glyph where the tick stands, tapping it opening the clarify flow on that item, as the row does.
