---
cairn: change
id: sync-strip-progress
status: active
created: 2026-10-10
---

# The sync strip counts contacts and calendars, and takes the controls' place

## Why

Device feedback on the strip (2026-10-10): the bar only ever fills for mail. A contacts or calendar pass runs indeterminate from end to end, so a first sync of a large book or agenda gives no idea how far it is. And the strip sits above the search field and the chips, so the header grows by the strip's height while it runs, with controls that have nothing to do with the wait.

## What

### Progress

The bar fills from what is actually counted, never from a guess:

- **Collections landed.** As mail counts an account's mailboxes, the contacts pass counts an account's address books (*Address books synced: 1 of 3*) and the calendar pass its calendars (*Calendars synced: 2 of 5*), as each one ends, failed or not.
- **Items within a step.** Two steps move items one batch at a time and can say so for free: the download that names the members a listing did not carry whole (64 bodies a read, `PimdirEngine.named`), and the projection onto the phone (one contact or event a write, `PhoneRemote.push`, `CalendarRemote.push`). While one runs, the bar measures it (*Downloading 230 contacts*, filling as the reads land). The engine tells a step's progress at most once per percent.
- **Between counts** (checking the server, reconciling with the phone, a hydrate whose bodies come back in one read, an upload), the bar holds what the account's collections came to, and runs indeterminate only before anything is counted.
- Calendars run three at a time: their item counts reach the strip only while one calendar runs alone (an account of one calendar, or the last one left), so the bar never jumps between three downloads.

The first projection of a book switched on for the phone fills the same way.

### Controls

While the strip shows (a pass, a background run, a first projection), the list's search field and chip row are hidden, and they come back when it goes. A search field holding a query stays: hidden, it would filter the list with nothing saying so and no way to clear it.

## Out of scope

- Counting uploads and conflict resolution: few items, one or two round trips.
- A count for the server exchange itself: the listing is one round trip and its size is unknown before it lands.
