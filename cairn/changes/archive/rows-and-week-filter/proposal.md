---
cairn: change
id: rows-and-week-filter
status: landed
created: 2026-10-07
---

# One row shape, and a week that filters

## Why

On the device, the contacts and agenda rows did not match the mail row: tighter padding, a leading time column on the agenda, a "this application" line on contacts. The unread dot read better beside the time than among the subject's marks. And a day in the week card scrolled the agenda, which on a sparse agenda looked like a filter that could not be undone.

## What

- Mail: the unread dot ends the sender's line, after the time; the nav badge sits over the envelope.
- Contacts and agenda rows take the mail row's shape: disc, three lines, hairline between rows. Contacts name their addressbook and account, preferring a card an account holds over the on-device one. The agenda row ends its first line with the start, its second with the kind of entry, and names its calendar and account.
- The week card's days toggle: pressing one narrows the agenda to that day, pressing it again widens it back to everything ahead. Days of this week already gone can be picked.
