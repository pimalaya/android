---
cairn: change
id: lists-from-the-index
status: draft
created: 2026-10-30
---

# Lists read the store's summaries and the index, never every body

## Why

The calendar tab is the one slow to first paint (the user, 2026-10-30). Timed on the device, a cold open in Week of 478 stored entries took 196 ms to one row: 103 ms reading every entry's body (`EventStore.loadEvents`), 44 ms parsing and expanding each in the bridge (`CalendarClient.expandEvent`), the cost growing with the store rather than with what is shown. Rows were never the issue: the `ListView` recycles them, and the render took 21 ms.

Reading the other lists' code (not timed) finds the same shape in two more:

- **Mail** pages `mail_summary` through pimdir's canonical keyset statements, lazily around the scroll; a body is read when a message opens. Already right.
- **Files** pages `list_files_page_asc`, no body; it reads every page up front rather than lazily.
- **Calendar** reads every body and expands every entry, a cache in memory empty at each start and dropped when the window moves.
- **Tasks** finds its to-dos through `task_summary`, then reads every body and rebuilds every task in the bridge (`TaskStore.load`, `CalendarClient.readTasks`), every reload.
- **Contacts** reads every card's body and indexes each through `ContactClient.indexCard` (`PimdirContacts.list`, whose SQL is transcribed into Java, against AGENTS.md), then regroups every card in the bridge (`ContactClient.groupContacts`), every reload.

The spec already says how a list is answered: the store answers the structured questions from its summaries (STORAGE Annex A, the canonical `list_*_page_asc`), and the index answers calendar time across a series (SEARCH.md §7, the `occurrence` table within a horizon, `occurring_between`). A list parsing every body on each display is the store used as a blob shelf.

## What

Decided with the user, 2026-10-30:

- **Calendar from the index.** The agenda lists from SEARCH.md §7's `occurrence` table through `occurrences_between` (one row per occurrence, added in search-draft-06) over the window shown (the week, or the list's), reading a body only to open an entry. io-pimdir's `search` feature is built into the Android cdylib, the index refreshed from the change feed after each sync pass and on start, and the agenda reads it through the bridge (io-pimdir's `PimdirIndex`), never by SQL in Java. A window outside the horizon says so, as SEARCH §8 asks of its coverage.
- **Tasks and Contacts: rows from the summaries, questions from the index.** Annex A grows only what a row shows or sorts by, io-pimdir's writers fill it, and the lists page the canonical statements alone:
  - `task_summary`: when it was captured (`CREATED`, else `DTSTAMP`), which Inbox, Waiting and Someday sort by, and who a Waiting For item waits on (`CONTACT`, its `ALTREP` address), likely through `item_address` as Annex A.6 files people;
  - Contacts list from `contact_summary` and `item_address` as they stand; what the Has filter tests (a phone, a postal address, a birthday, a photo) and the card's categories are the index's (`has:` and `category:` on cards, recorded at extraction), not summary columns (the user, 2026-10-30: summaries hold the row, the index the questions).
- **Text stays the index's.** A search over a task's description or a card's notes is SEARCH's full text, not a summary column.
- **Contacts' grouping** (the merged replicas) is computed from summary columns rather than from bodies; whether it is kept between reloads is to be settled with the grouping's owner (`ContactClient.groupContacts`).

## Depends on

The user asked to wait (2026-10-30): pimdir and io-pimdir hold uncommitted work in progress (io-pimdir's `search-feature`, pimdir's draft edits), and nothing here edits either tree until it is committed. In order:

1. pimdir: the Annex A columns above, a proposal in pimdir's cairn, migrations and the canonical statements updated.
2. io-pimdir: `search-feature` landed and released or pushed; the Annex A writers; the app's pinned rev moved.
3. This app: `search` on in rust/Cargo.toml, the refresh wired after sync, the three lists moved.

## Blocked on the draft-05 port (2026-10-30, night)

`search` exists from io-pimdir 2e4ab95, and moving the pin there is a port of the store rules draft-05 added, which the Java store re-implements by hand: cairn/changes/io-pimdir-draft-05, four steps with device checks, not run unattended.

## Decide before building

- **Two SQLites, one file.** The bridge builds io-pimdir without `client`, and `search` requires it: the index's refresh would open pimdir.db (attached read-only) through rusqlite's bundled SQLite while the Java store writes it through requery's. Two SQLite copies in one process on one file is the case sqlite.org's "How To Corrupt An SQLite Database File" §2.2 names: closing a descriptor in one copy drops the POSIX locks the other holds. Ways out: the refresh in a process of its own (the sync service's), the Java store moved behind the bridge (cairn/log/2026-10-15-jni-boundary.md lists storage-in-Rust as later work), or the index statements run by the Java side on requery, which bundles FTS5 (to verify) but breaks "never transcribe SQL into Java" unless the statements are vendored as the store's are.

## To check before building

- The cdylib's size with `search` (mail-parser, FTS5) and an index refresh's cost on a phone after a large first sync.
- FTS5 in the bundled requery SQLite, should the Java side ever open index.db (it should not: the bridge reads it).
- A one-off event spanning into the window from before it, and a `DURATION` without `DTEND`: §7 spans both, which the window by start the app uses today misses.

## Out of scope

- The calendar's first-paint placeholder (the empty state showing until the first load lands), a separate small fix.
