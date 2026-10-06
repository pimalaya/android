---
cairn: change
id: google-apis
status: landed
created: 2026-10-06
---

# Read Google mail and calendars over their APIs

## Why

A Google address was offered mail over IMAP and calendars over CalDAV only. The provider rule names the Gmail and Calendar APIs too, but the discovery merge dropped them and no backend read them, the same gap Graph mail had at Microsoft.

## What

- A Gmail API mail backend behind `google://`: labels as mailboxes, an incremental round replaying the history from a `historyId`, the source read raw, `\Seen` and `\Flagged` as `UNREAD` and `STARRED`, deletes into `TRASH`, sending through `messages.send`.
- A Google Calendar API backend behind `google://`: the calendar list, series read with their instances through the `iCalUID`, a revision folding the instances' ETags, writes with `If-Match`, creates imported to keep their UID.
- Both offered at Google, Gmail reading sending through Gmail.
