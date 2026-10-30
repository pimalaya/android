---
cairn: change
id: one-filter-panel
status: active
created: 2026-10-30
---

# One inline filter panel per list, and what syncs moves to account settings

Design: the canvas row "Filters, one panel; sync apart (proposed)", boards Filters 1 to 4. Moves the sync half of `mail-filter-apply` (2026-10-12) to account settings: settings decide what is on the phone, the filter what the current view keeps.

## Why

User feedback (2026-10-30):

- The Tasks header does not read like the other lists: it says when the review was last done instead of a count, has no search, and stacks two chip rows.
- A list is narrowed in three places: the bar's filter page (accounts and collections), the chip rows (roles, GTD lists, statuses, categories, unread, attachments) and the search. One place is simpler.
- The filter page also decides what syncs (an unticked mailbox is synced no more, *Download* keeps one whole). That is a setting, not a view: hiding Sent for a minute should not stop Sent from syncing.
- Chips do not scale: with many projects the category row is unusable.

## What

### Every list header reads the same

- Large title, a count line, the search field, the filter panel or its pills, then the rows. Tasks gets a count ("12 tasks") and a search over the summary and categories; the review line leaves the header, the bar's Weekly review button reads accented while the review is due. Task rows keep square checkboxes.
- The bar loses its filter button; the filter button sits at the right end of the search field, accented while any filter is on; it unfolds and folds the panel.

### One inline filter panel

- The filter button unfolds the panel right under the search field, inside the list's header, and folds it back. It is part of the header and scrolls with the rows.
- The panel is one row per section, naming the section and its current value ("Status: All", "Categories: Garden"); tapping a row opens its checklist under it and closes any other, so the panel stays a few rows tall whatever the number of projects.
- Checklists, not chips: a single choice is a radio list, a multiple one a checkbox list with square boxes. A checklist past 6 entries shows the first 6, the ones on first, and "Show all N".
- Ticks edit a draft: the panel's foot carries *Reset* (the draft back to the defaults), *Cancel* (the draft dropped) and *Apply* (the draft kept, the list refiltered); both fold the panel. The panel grows and shrinks in height as it opens and folds. The filter button stays while it is open, a tap folding it as Cancel does, and each section's chevron stands on its centre line.
- Folded, the panel leaves one pill per criterion on under the search field (the Show choice, each project, "Due before 7 Nov 2026", the calendars shown); tapping a pill removes that criterion alone and refilters. Nothing on, no pill.
- Sections per list:
  - Mail: Show (All, Inbox, Sent, Drafts, Trash, Junk, Archive; a radio list, All by default), Only (Unread, With attachments), Received (Any time, Before, On, After a date), Mailboxes (accounts and their mailboxes).
  - Tasks: in this order: Calendars, Status, Categories (Inbox, Waiting, Someday, then every category any task carries, the ones on first, past 6 behind "Show all N"; a task passes when it carries any one on), Status (To do, In progress, Done, Cancelled; any number), Due (Any time, Before, On, After a date), Calendars (every calendar the calendar filter syncs and that takes to-dos, under its account). With nothing on, the open tasks first (to do and in progress), then the done ones dimmed as a mail row with no body is, then the cancelled ones dimmed and struck through; "Next" is no longer a word the list uses.
  - Calendar: Show (All, Default), Calendars.
  - Contacts: Show (All, Default), Address books, the on-device book included.
  - Files: what its filter page offers today.
- The chip rows and the filter page overlay go.
- Filters are kept per list across restarts, as the filter is today; the search and whether the panel is unfolded are not.

### What syncs moves to account settings

- The filter narrows the view and nothing else: it never starts, stops or narrows a sync.
- An account's settings gain one section per domain it covers ("Mailboxes that sync", "Calendars that sync", "Address books that sync"), a switch per collection, and *Keep whole* / *Stop* on each mailbox (today's *Download*). This is what every sync, pull, background run, body download and *Load more* reads, `SyncScope` moving off `MergedFilter`.
- Switching a collection on starts its first sync as *Apply* does today (first chunk of about 50, its own frontier, on Wi-Fi); off stops every sync of it and keeps what is stored.
- Migration: each account's sync switches start as today's filter ticks (the two are the same set today); every view filter starts at its default, showing everything synced.
- Defaults for a new account are today's filter defaults, moved: inbox alone off Gmail, every label but Trash and Spam on Gmail.

### Steps, each checked on the device before the next

1. Tasks header: count, search, review button accent.
2. Sync scope into account settings; the filter page loses *Download* and its sync effects.
3. The inline panel and its pills, list by list: Tasks first, then Mail, Calendar, Contacts, Files.

## Out of scope

- Saved filters or presets.
- A date filter on the agenda (it moves by week) and on contacts.

## Open

- Switching Inbox to Sent, or the Inbox to Waiting, is one chip tap today; it becomes three folded (filter button, section row, choice) and two unfolded. If it hurts on the device, the panel could unfold with Show already open.
- Tasks' Show merges the two chip rows (GTD list and status) into one choice: "Waiting among the done ones" can no longer be asked. Nobody is known to need it.
- Which date "Due" reads: `DUE` alone; a task with none never passes a Due filter.
- Mail "Received" reads the `Date` header, the same one the account's months bound reads.
