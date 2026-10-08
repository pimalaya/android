---
cairn: tasks
change: collection-filter-page
---

# Tasks

## 1. Filter
- [x] `MergedFilter` keyed by collection id, persisted per domain; migration from the in-memory name sets (start with everything shown)
- [x] Filter page (replaces the dialog): accounts with tri-state checkbox, collections below, Reset
- [x] Mail queries filter by collection id (MailStore.java:645, MailList.java:230, MainActivity.java:1411) instead of mailbox name
- [x] Account on and off in its settings (`AccountActivation`): off syncs nothing and leaves every filter and list
- [x] Pull-to-refresh scoped by the tab's filter, "Sync all" by the accounts that are on (`SyncScope`)

## 2. Roles
- [x] Mail role chips (Inbox, Sent, Drafts, Trash, Junk, Archive; none on narrows nothing); a chip narrows the shown collections to that role (pimdir collection roles)
- [x] Default chip for contacts and calendars

## 3. Default collection
- [x] Bridge sets `default`: JMAP `isDefault`, Graph `isDefaultCalendar` / default contacts folder, Google `primary` / `myContacts`; and writability (JMAP `myRights`, Graph `canEdit`, Google `accessRole`)
- [x] Contacts sync restates the books' roles and writability
- [x] App fallback: only writable collection of its kind; else user's "Set as default" (app preference)
- [x] `addContact` / `composeEvent` use the default; picker otherwise, writable only, default preselected
- [x] Tests: role set from each source; fallback; a read-only calendar never offered; filter persistence, tri-state, same-named mailboxes, role chips, pull scope

## 4. Land
- [x] Fold the delta into mail and offline-store; log; CHANGELOG; archive
