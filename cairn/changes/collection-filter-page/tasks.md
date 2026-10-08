---
cairn: tasks
change: collection-filter-page
---

# Tasks

## 1. Filter
- [ ] `MergedFilter` keyed by collection id, persisted per domain; migration from the in-memory name sets (start with everything shown)
- [ ] Filter page (replaces the dialog): accounts with tri-state checkbox, collections below, Reset
- [ ] Mail queries filter by collection id (MailStore.java:645, MailList.java:230, MainActivity.java:1411) instead of mailbox name

## 2. Roles
- [ ] Mail role chips; a chip narrows the shown collections to that role (pimdir collection roles)
- [ ] Default chip for contacts and calendars

## 3. Default collection
- [ ] Bridge sets `default`: JMAP `isDefault`, Graph `isDefaultCalendar` / default contacts folder, Google `primary` / `myContacts`
- [ ] App fallback: only writable collection of its kind; else user's "Set as default" (app preference)
- [ ] `addContact` / `composeEvent` use the default; picker otherwise, writable only, default preselected
- [ ] Tests: role set from each source; fallback; a read-only calendar never offered

## 4. Land
- [ ] Fold into spec (mail, calendar, contacts, the merged view); log; CHANGELOG
