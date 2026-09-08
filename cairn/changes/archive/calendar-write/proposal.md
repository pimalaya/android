---
cairn: change
id: calendar-write
status: landed
created: 2026-09-03
---

# Let a calendar entry be created and deleted

## Why

The calendar page reads and edits, and the two verbs around editing are missing: an entry cannot be created and cannot be deleted. The add button on the agenda opens a frame with a placeholder in it, and an entry once created can only ever be edited.

Editing already works end to end (a patch through the syntax tree, pushed guarded by the entry's ETag), so both missing verbs are the same push with a different precondition: a create is guarded on the resource not existing, a delete on it not having moved.

## What

**Create.** The agenda's add button opens the entry page on a new object rather than a placeholder: a fresh `VEVENT` carrying the `UID`, `DTSTAMP` and `DTSTART` a valid object needs (RFC 5545 section 3.6.1) and nothing else, so every row of the page is the user's to fill. Saving it PUTs to a resource named after the `UID`, guarded by `If-None-Match: *` so a collision is refused rather than overwriting whatever is there.

Which calendar it lands in is asked when more than one is writable, and taken silently when there is one.

**Delete.** The entry page gains a delete, guarded by the entry's ETag and confirmed first.

Both are CalDAV, like the edit beside them: a JMAP calendar takes `CalendarEvent/set` in JSCalendar, which is the conversion the read path does in the other direction and is not written yet, so it refuses rather than silently doing nothing.
