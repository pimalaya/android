---
cairn: delta
change: lists-from-the-index
---

Folds into spec/calendar.md, spec/tasks.md and spec/offline-store.md, each requirement naming its file.

## ADDED Requirements

### Requirement: The agenda reads the index's occurrences (spec/calendar.md)
The agenda SHALL list the occurrences the search index wrote of every live entry over the window shown (the week, or the list's), through its `occurrence` table on `occurrences_between`'s rule (pimdir SEARCH §7), each row's title, place, kind and all-day mark read from its entry's summary (STORAGE Annex A.3 to A.5), and SHALL read an entry's body only to open it, the occurrence it opens expanded from that body at the row's instant. An entry deleted since the index folded it SHALL not be listed. The index SHALL read floating times and dates in the device's zone, so a floating time shows at the wall time it states, and the agenda SHALL place an all-day occurrence on its day in the zone the index records (`index_meta.floating_zone`); a change of the device's zone SHALL refresh the index, which reads them again. A window reaching outside the index's horizon, or past a series' cut (10,000 occurrences, 1,000,000 periods of a rule), SHALL say so in the list's header. The agenda SHALL reload once a refresh of the index folded anything. With no index, or one refused, the agenda SHALL expand every entry instead, as before.

#### Scenario: A thousand entries
- GIVEN a calendar of a thousand entries, three of them this week
- WHEN the agenda shows this week
- THEN it lists the three from the index and reads no body

#### Scenario: A floating stand-up
- GIVEN a weekly entry at 09:00 with no zone, the device in New York
- WHEN the agenda shows next week
- THEN the stand-up starts at 09:00 on Monday

#### Scenario: Three years on
- GIVEN the week card moved three years ahead
- WHEN the agenda shows that week
- THEN its header says the week is outside the years the index holds

#### Scenario: A secondly series from 1970
- GIVEN an event `RRULE:FREQ=SECONDLY` starting in 1970
- WHEN the agenda shows this week
- THEN it places nothing of it, and its header says one series is not shown in full

#### Scenario: An entry deleted
- GIVEN an entry of this week
- WHEN it is deleted from its page
- THEN it leaves the agenda before the index has folded the delete

### Requirement: The contacts list reads the summaries and the index (spec/offline-store.md)
The contacts list SHALL read its rows from the store's summaries (STORAGE Annex A.2, A.6): a card's name its `FN`, else its first email, else its organization; its first email; its organization; its kind; and SHALL read no body to list, filter or search. What the Has filter tests beyond an email and an organization (a phone, a postal address, a birthday, a photo) and a card's categories SHALL be what the search index recorded of its body (pimdir SEARCH §6, `object` and `object_category`), the categories offered as the index folds them; with no index, or one refused, they SHALL be read off the bodies as before. The search SHALL keep a card whose name, email or organization holds the words, or which the index finds the words in as a query of SEARCH §8 (`kind:contact`), its notes and phones included. A merged row SHALL be marked diverged by comparing its cards' bodies only when they differ byte for byte, each body's comparison kept for the process. The birthday peek SHALL read the bodies of the cards the index records a birthday of alone.

#### Scenario: A card whose body is unread
- GIVEN a card whose body is not in the blob directory
- WHEN the contacts list loads
- THEN it lists the card under its name and first email

#### Scenario: A note
- GIVEN a card whose note alone says `met at the crag`
- WHEN `crag` is searched
- THEN the card is listed

## MODIFIED Requirements

### Requirement: The agenda builds off the main thread (spec/calendar.md)
The agenda SHALL read its window on a thread of its own and draw the result on the main thread, the last reload asked winning. With no index, an entry's expansion SHALL be kept while the window shown (the week, or the list's) and its object stay the same.

#### Scenario: Coming back to the app
- GIVEN 10k stored entries
- WHEN the app returns to the foreground
- THEN the screen stays responsive while the agenda rebuilds, and no body is read

### Requirement: The Tasks tab reads its to-dos from the summaries (spec/tasks.md)
The Tasks tab SHALL list its to-dos from the store's summaries alone (STORAGE Annex A.4, A.6): the items holding a `task_summary` row, their state and categories from their `task_category` rows, who a Waiting For item waits on from its first `contact` address, when it was captured from the row's `created`, and SHALL read no body to list them; a task's page, the clarify flow and a copy SHALL read its body, its description included. A calendar item the store holds with no summary row SHALL have one derived through io-pimdir, once, before the tab reads.

#### Scenario: An agenda of events
- GIVEN a calendar holding a thousand events and one to-do
- WHEN the Tasks tab loads
- THEN it lists the to-do and reads no body

#### Scenario: A body unread
- GIVEN a Waiting For to-do whose body is not in the blob directory
- WHEN the Tasks tab loads
- THEN it lists the to-do under Waiting, with its contact and how long it has waited

#### Scenario: A store from before summaries
- GIVEN a store whose calendar items were written with no summary
- WHEN the Tasks tab first loads
- THEN `task_summary` holds a row per to-do and the tab lists them all

### Requirement: The three lists share one row (spec/offline-store.md)
A contact row and an agenda row SHALL take the mail row's shape: a disc, then three lines, a hairline parting the rows of a card. A contact row SHALL lead with the name, then the first email or else the organization, as the store's summary holds them, then its addressbook and account, naming a card an account holds before one on the device. An agenda row SHALL lead with the entry ended by when it starts, then the kind of entry by name and how long it runs, then its calendar and account.

#### Scenario: A contact on two cards
- GIVEN a contact held in the on-device book and in an account's addressbook
- WHEN the contacts list is shown
- THEN its row names that addressbook and that account

## REMOVED Requirements

### Requirement: The agenda's build is timed (spec/calendar.md)
Removed: the agenda read and drawn logs measured the move to the index (cairn/log/2026-11-01-lists-from-the-index.md) and are dropped, as cairn/changes/platform-sqlite step 5 planned.
