---
cairn: change
id: lists-from-the-index
status: active
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

- **Two SQLites, one file.** The bridge builds io-pimdir without `client`, and `search` requires it: the index's refresh would open pimdir.db (attached read-only) through rusqlite's bundled SQLite while the Java store writes it through requery's. Two SQLite copies in one process on one file is the case sqlite.org's "How To Corrupt An SQLite Database File" §2.2 names: closing a descriptor in one copy drops the POSIX locks the other holds. Checked 2026-10-31: rusqlite without `vendored` cannot link requery's libsqlite3x.so instead, which exports 252 `sqlite3_*` functions and lacks ones rusqlite calls on every statement (`sqlite3_bind_parameter_count`, `sqlite3_clear_bindings`, `sqlite3_bind_parameter_index`) and `sqlite3_create_function_v2`; nor the platform's, outside the NDK and refused at load since API 24. Settled by cairn/changes/platform-sqlite: an I/O-free refresh, Java writing index.db on the store's SQLite, this change its step 5.

## To check before building

- The cdylib's size with `search` (mail-parser, FTS5) and an index refresh's cost on a phone after a large first sync.
- FTS5 in the bundled requery SQLite, should the Java side ever open index.db (it should not: the bridge reads it).
- A one-off event spanning into the window from before it, and a `DURATION` without `DTEND`: §7 spans both, which the window by start the app uses today misses.

## Out of scope

- The calendar's first-paint placeholder (the empty state showing until the first load lands), a separate small fix.

## Built at platform-sqlite step 5 (2026-11-01)

The two-SQLites question is settled by cairn/changes/platform-sqlite (one SQLite, the index written by Java, io-pimdir's refresh I/O-free), and the rev moved with it: the lists moved on the index step 4 built. What was taken where the text above leaves room, each the option it states or the nearest to the status quo:

- **The contact row's second line** is the first email, else the organization: Annex A.2 and A.6 "as they stand" hold no phone, so the phone the row showed first is gone (spec/offline-store.md "The three lists share one row" said "the phone or else the email"). A phone on the row would need a summary column, which this proposal ruled out; to settle with the user at the device check.
- **The contacts' Has filter and categories** read the index's `object` flags and `object_category` rows for every card at each reload (the same rows `has:` and `category:` seek), one read, the panel offering the categories folded as the index records them. The search is the name, email and organization match beside a `kind:contact` query of SEARCH §8, as the mail search keeps its sender and subject match beside the index's hits.
- **The divergence mark** of a merged row compares the cards' bodies only when they differ byte for byte, the normalized hash kept per body hash for the process: no summary holds the normalized content.
- **The grouping kept between reloads** stays open, as the text says; it is recomputed at each reload, now from summary columns (uid, fn, link id), which costs no body.
- **The Tasks description** is not a summary column: the list holds none, and the page, the clarify flow and a copy read the body (`TaskStore.Stored.full`).
- **The floating zone** the index reads floating times and dates in is the device's (io-pimdir's `PimdirIndexRefresh::floating_zone`, `TimeZone.getDefault()`), so the index re-expands every calendar item once when this build first refreshes, and again after a change of zone; the agenda reads an all-day occurrence's day in the zone `index_meta` records.
- **The agenda's coverage** is said on the header's count line: outside the horizon, or a number of series cut before the window's end.
- **With no index or one refused**, the agenda expands every body as before, and the contacts' facts are read off the bodies: a refused index (another shape, a later version) leaves the lists whole, slower.
- **To check before building**, answered: the cdylib's size moved at step 4 (+0.6 MB with `search`); FTS5 is moot (FTS4, and Java opens index.db for the bridge alone); a `DURATION` without `DTEND` is spanned by the occurrence table, and an event spanning into the window from before it is read on the overlap rule (SEARCH §8.1); the agenda still draws a row on the day it starts, so such an event is not drawn on the window's days, as before.
