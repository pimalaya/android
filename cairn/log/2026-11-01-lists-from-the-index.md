---
cairn: log
change: lists-from-the-index
date: 2026-11-01
---

# The agenda, Tasks and Contacts list from the summaries and the index

Built as cairn/changes/archive/platform-sqlite step 5, on the index step 4 built. The agenda reads the search index's occurrence table, Tasks and Contacts the store's summaries, and none of the three reads a body to list. The change stays in flight until the device check (cairn/changes/archive/lists-from-the-index/tasks.md); its delta (delta.md) folds at landing.

Capabilities moved: calendar, tasks and offline-store (folded at landing, not yet: cairn/changes/archive/lists-from-the-index/delta.md).

## The agenda (checkpoint c)

- **From the index.** rust/pimdir/queries/index/agenda_occurrences.sql reads `occurrences_between`'s rows of the window (SEARCH §7, the same rule and order), joined on the attached store's items (live, holding a body) and their summaries (`event_summary`, `task_summary`, `journal_summary`) and the server binding: each row's component, title, place, all-day mark, handle and revision, no body. agenda_cut.sql counts the live items whose `cut` lies before the window's end. rust/src/search/lists.rs `agenda` runs both with `load_index_meta` in one round and answers the horizon, `horizonLeft` (the window starting before it or ending after it, or no horizon yet) and `floatingZone`; `Native.pimdirIndexAgenda`, `PimdirIndex.agenda`, `PimdirIndexDb.agenda`.
- **CalendarList.rowsOf** asks the index for the window (its civil days, a day of slack either side as before, as UTC instants), builds each row's `Occurrence` from the instants (a timed one as UTC, an all-day one as its day in the zone the index records) and a bodiless `StoredEvent` (`EventStore.bodiless`, its body read on first `ical()`), marks conflicts as before. Opening a row reads the body and expands it over the row's day, taking the occurrence starting at the row's instant, its own title and `RECURRENCE-ID` (an override's), else the row's. With no index, or one refused, `expandedRows` expands every body as before, its cache kept.
- **The floating zone.** The refresh states the device's zone (`TimeZone.getDefault()`, `PimdirIndexRefresh::floating_zone`, a new argument of `refresh::run` and `Native.pimdirIndexRefresh`), so a floating time is written at the wall time it states; io-pimdir re-expands every calendar item when the zone differs from `index_meta.floating_zone`, so the first refresh of this build re-expands them once (UTC before). `ZoneChange` asks a refresh on a zone change, whatever the calendar permission.
- **Coverage on the header.** The count line says `…, outside the years the index holds` when the window leaves the horizon, and `…, N series not shown in full` for the cut (two new strings, English and French; no new view or style).
- **Freshness.** A row of an entry deleted since the last refresh is left out at once (the join on live items). An edit or a create shows once the index folds it: `PimdirIndexDb.onFolded` runs after a refresh that folded rows, and the activity reloads the agenda, or the contacts, when shown.

## Tasks (checkpoint b)

- rust/pimdir/queries/app/list_task_rows.sql reads every live to-do of the calendars: binding handle and revision, the `task_summary` row and the first `contact` address; list_task_categories.sql their `task_category` rows. `EventStore.loadTasks` derives missing summaries as before, then reads both, the bodiless objects beside, and hands the two tables to the bridge as `{columns, rows}` (`PimdirDb.table`), Java naming no column.
- rust/src/calendar/task.rs `tasks_of` reads them the GTD way as `task` reads a body: state and categories split by `state_of`, priority 0 to 9, times through `EventTime::of_summary` (no offset: the object's own `VTIMEZONE` is the body's), `created` written back as the iCalendar stamp `capturedAt` parses, the description empty. `Native.tasksOf`, `CalendarClient.tasksOf`.
- `TaskStore.Stored.full()` reads the body when the page opens, saves, clarifies or copies a task.

## Contacts (checkpoint a)

- rust/pimdir/queries/app/list_cards.sql reads, beside the binding and conflict columns, the item's seq and account, `contact_summary`'s uid, fn, kind and org and the first `email` address. `PimdirContacts.Indexed` holds them, its card read on first `card()`, its normalized hash on first `hash()`, kept per body hash for the process.
- rust/pimdir/queries/index/card_facts.sql and card_categories.sql read the index's `object` flags (`tel`, `adr`, `bday`, `photo`) and `object_category` rows of every card placement; `lists::card_facts`, `Native.pimdirIndexCardFacts`, `PimdirContacts.facts`, `CardFacts.Indexed`. With no index they are read off the bodies (`CardFacts.fromBodies`).
- `ContactsList`: Has tests the email and organization on the summary and the rest on the facts; Kind the summary's kind; Categories the facts, offered as the index folds them; the search matches name, email and organization, and the cards a `kind:contact (…)` query hits (`PimdirContacts.found`, off the main thread). The row's second line is the first email, else the organization; the avatar is seeded by the body's hash. `ContactPool.group` marks a merged row diverged by parsing only bodies that differ byte for byte. The birthday peek reads the cards the index says hold a `BDAY`; the duplicate scan reads its bodies off the main thread.

## Measured

On the host (`AgendaMeasure`, run by hand with `PIMALAYA_MEASURE` set: 478 entries, one in six a weekly series, the platform's classes over SQLite 3.18.2, seven rounds): a week, 56 rows, read by expansion (every body read and expanded, the old path) in 18 ms median (24 ms first), from the index in 3 ms (7 ms first); the list's 92 days, 668 rows, 25 ms against 15 ms; the first index build of the 478 entries 521 ms. Both paths answer the same rows. On the device the old path was 196 ms to first paint, 103 ms of bodies and 44 ms of expansion (2026-10-30); the device's figure after is not taken: the `agenda read` and `agenda drawn` logs are dropped, as the plan asks.

## Tests

Failing first, checked by running a probe of the behaviour against HEAD's sources (tracked files put back from `git show HEAD:`, then restored): a card whose body is gone from the blob directory lists named `Mia` (`expected:<Mia> but was:<>`); a captured task whose body is gone lists (`expected:<1> but was:<0>`); a floating weekly entry refreshed in UTC, the zone the refresh always stated, starts at 09:00Z rather than 08:00Z in Paris (`the_agenda_reads_occurrences_with_their_rows`, `left: "UTC"`). The other new tests call APIs HEAD has not, so they fail first by not compiling: `PimdirContactsTest.aCardListsFromItsSummaryWithItsBodyUnread`, `theIndexAnswersWhatACardHoldsAndItsWords`; `TaskWritesTest.aTaskListsFromItsSummaryWithItsBodyUnread`; `CalendarAgendaTest` (a window off the index with no body, a floating time and an all-day day under New York, a deleted entry leaving before the index folds it, a window past the horizon); Rust `a_todo_reads_off_its_summary_row`.

One assertion moved: `TaskWritesTest.aCaptureLandsInTheInboxDatelessWithItsMail` read the description off the listed task; it reads it off `full()`, the list holding the summary alone. Accessors renamed in tests, assertions unchanged: `card.vcard()` to `card().vcard()`, `entry.card.id()` to `entry.id`, `event.ical` to `event.ical()`.

## Spec gaps

- No summary column holds a card's phone (Annex A.2, A.6), so the contact row's phone line went; a row showing one needs pimdir to add it.
- Annex A.3 holds the master's `SUMMARY` and `LOCATION`: an override's own title shows on its page, not on its agenda row.
- A summary's times carry no offset: a task's `DUE` in a `TZID` only its own `VTIMEZONE` defines (a Windows name the platform cannot map) reads in the device's zone on the list.
- `index_meta.floating_zone` is one zone: an all-day entry is placed on its day in it, so a list read while the device's zone differs from it (between a zone change and the refresh after) shows dates read in the old one.
