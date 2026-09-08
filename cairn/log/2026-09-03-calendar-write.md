---
cairn: log
change: calendar-write
landed: 2026-09-03
---

# Let a calendar entry be created and deleted

The two verbs around editing landed, so the calendar domain writes as well as it reads.

A new entry is built before its page opens rather than at save time, from a bridge function that emits one component carrying the `UID`, `DTSTAMP` and `DTSTART` RFC 5545 requires of it and nothing else: what the page then edits is an object like any other, and the save is the only thing that knows the server has not seen it. It is placed at the next whole hour, which is the guess that needs the least correcting. The object is parsed back before it is returned, an object the page could not open being worse than an error.

Both pushes are guarded, like the edit between them: a create on the resource not existing (`If-None-Match: *`, so a `UID` collision is refused rather than overwriting), a delete on the ETag the entry was read at. The agenda's add button now opens the page instead of a placeholder, asking which calendar when there is more than one, and the placeholder panel is gone with it.

Spec updated: `calendar` (ADDED: a calendar entry can be created, a calendar entry can be deleted).
